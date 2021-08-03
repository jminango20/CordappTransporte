package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.DeliveryOrderContract
import com.template.contracts.PurchaseOrderContract
import com.template.contracts.StockContract
import com.template.internal.GetStockStateAndRefFlow
import com.template.states.DeliveryOrderState
import com.template.states.PurchaseOrderState
import com.template.states.Stock
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

object CreateDeliveryOrderFlow {

    /*
        Parties:
        -Distribuitor
        -Producer
     */

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val deliveryCompany: Party,
        private val purchaseOrderStateID: UniqueIdentifier
    ) : FlowLogic<UniqueIdentifier>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call(): UniqueIdentifier {
            val deliveryOrderStateID = createDeliveryOrder()
            consumePurchaseOrder()
            removePurchaseOrderInventoryFromStock()

            return deliveryOrderStateID
        }


        @Suspendable
        private fun createDeliveryOrder() : UniqueIdentifier {
            val purchaseOrderStateAndRef = serviceHub
                .vaultService
                .queryBy<PurchaseOrderState>(
                    QueryCriteria.LinearStateQueryCriteria(linearId = listOf(purchaseOrderStateID))
                )
                .states
                .single()

            val transactionBuilder = TransactionBuilder(notary = purchaseOrderStateAndRef.state.notary)

            val products = getCurrentStock().reserved[purchaseOrderStateID]
                ?: throw IllegalArgumentException("Unable to find purchase order.")


            val deliverOrderSate = DeliveryOrderState(
                buyer = purchaseOrderStateAndRef.state.data.buyer,
                seller =  ourIdentity,
                deliverCompany = deliveryCompany,
                products = products
            )

            transactionBuilder
                .addOutputState(state = deliverOrderSate, contract = DeliveryOrderContract::class.qualifiedName!!)
                .addCommand(
                    data = DeliveryOrderContract.Commands.Create(),
                    keys = listOf(ourIdentity.owningKey, deliveryCompany.owningKey)
                )

            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

            val session = initiateFlow(deliveryCompany)
            session.send(true)
            val buyerSession = initiateFlow(purchaseOrderStateAndRef.state.data.buyer)
            buyerSession.send(false)

            val fullySignedTransaction = subFlow(CollectSignaturesFlow(signedTransaction, listOf(session)))
            val finalTransaction = subFlow(FinalityFlow(fullySignedTransaction, listOf(session, buyerSession)))

            return finalTransaction.tx.outputsOfType<DeliveryOrderState>().single().linearId
        }

        @Suspendable
        private fun consumePurchaseOrder(){
            val purchaseOrderStateAndRef = serviceHub
                .vaultService
                .queryBy<PurchaseOrderState>(
                    QueryCriteria.LinearStateQueryCriteria(linearId = listOf(purchaseOrderStateID))
                )
                .states
                .single()

            val transactionBuilder = TransactionBuilder(notary = purchaseOrderStateAndRef.state.notary)

            transactionBuilder
                .addInputState(purchaseOrderStateAndRef)
                .addCommand(
                    data = PurchaseOrderContract.Commands.Consume(),
                    keys = listOf(ourIdentity.owningKey)
                )

            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)
            val session = initiateFlow(purchaseOrderStateAndRef.state.data.buyer)
            session.send(false)
            subFlow(FinalityFlow(signedTransaction, listOf(session)))

        }

        @Suspendable
        private fun removePurchaseOrderInventoryFromStock(){
            val stockStateAndRef = subFlow(GetStockStateAndRefFlow())

            val transactionBuilder = TransactionBuilder(notary = stockStateAndRef.state.notary)

            val stock = stockStateAndRef.state.data.stock
            val newReserved = stock.reserved.toMutableMap()
            newReserved.remove(purchaseOrderStateID)
            val newStockState = stockStateAndRef.state.data.copy(
                stock = stock.copy(reserved = newReserved)
            )

            transactionBuilder
                .addInputState(stockStateAndRef)
                .addOutputState(state = newStockState, contract =  StockContract::class.qualifiedName!!)
                .addCommand(data = StockContract.Commands.RemoveReservedProducts(), keys = listOf(ourIdentity.owningKey))

            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder, listOf(ourIdentity.owningKey))
            subFlow(FinalityFlow(signedTransaction, listOf()))
        }

        @Suspendable
        private  fun getCurrentStock(): Stock {
            return  subFlow(GetStockStateAndRefFlow())
                .state
                .data
                .stock
        }
    }

    /*
      Parties:
      -Transporter
      -Retail
      -Distributor
   */

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val shouldSign = counterpartySession.receive<Boolean>().unwrap { it }

            if (shouldSign){
                val signedTransaction = subFlow(object : SignTransactionFlow(counterpartySession){
                    @Suspendable
                    @Throws(FlowException::class)
                    override fun checkTransaction(stx: SignedTransaction) {
                        val currentCommand = stx.tx.commands.single().value
                        val currentSigners = stx.tx.commands.single().signers.toSet()
                        val expectedSigners = setOf(ourIdentity.owningKey, counterpartySession.counterparty.owningKey)

                        requireThat {
                            "Command should be of type DeliveryOrderContract.Commands.Create. Got: $currentCommand" using
                                    (currentCommand is DeliveryOrderContract.Commands.Create)

                            "Wrong signers. Expected: $expectedSigners, Got: $currentSigners" using
                                    (currentSigners == expectedSigners)
                        }
                    }
                })
                subFlow(ReceiveFinalityFlow(counterpartySession, signedTransaction.id))

                return
            }

            subFlow(ReceiveFinalityFlow(counterpartySession))
        }

    }

}