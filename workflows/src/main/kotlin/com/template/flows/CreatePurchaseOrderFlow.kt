package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.PurchaseOrderContract
import com.template.internal.GetStockStateAndRefFlow
import com.template.internal.ReserveProductFlowInitiator
import com.template.states.Product
import com.template.states.PurchaseOrderState
import com.template.states.Stock
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object CreatePurchaseOrderFlow {

    /*
        Parties:
        -Retail
        -Distribuitor
     */
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
            private val seller: Party,
            private val productsToBuy: Map<Product.Type, Int>, //Number of products to buy of each type
            private val valueInCents: Int
    ) : FlowLogic<UniqueIdentifier>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call(): UniqueIdentifier {
            // Initiator flow logic goes here.
            val notary = serviceHub.networkMapCache.notaryIdentities.first()

            val transactionBuilder = TransactionBuilder(notary = notary)

            val purchaseOrderState = PurchaseOrderState(
                    buyer = ourIdentity,
                    seller = seller,
                    products = productsToBuy,
                    valueInCents = valueInCents
            )

            transactionBuilder
                    .addOutputState(state = purchaseOrderState, contract = PurchaseOrderContract::class.qualifiedName!!)
                    .addCommand(
                            data = PurchaseOrderContract.Commands.Create(),
                            keys = listOf(ourIdentity.owningKey, seller.owningKey)
                    )

            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)
            val session = initiateFlow(seller)
            val fullySignedTransaction = subFlow(CollectSignaturesFlow(signedTransaction, listOf(session)))
            val finalTransaction = subFlow(FinalityFlow(fullySignedTransaction, listOf(session)))

            return  finalTransaction.tx.outputsOfType<PurchaseOrderState>().single().linearId
        }
    }

    /*
      Parties:
      -Distribuitor
      -Producer
   */
    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            // Responder flow logic goes here.
            val signedTransaction = subFlow(object : SignTransactionFlow(counterpartySession){
                @Suspendable
                @Throws(FlowException::class)
                override fun checkTransaction(stx: SignedTransaction) {
                    val currentCommand = stx.tx.commands.single().value

                    val currentSigners = stx.tx.commands.single().signers.toSet()
                    val expectedSigners = setOf(ourIdentity.owningKey, counterpartySession.counterparty.owningKey)

                    val products = stx.tx.outputsOfType<PurchaseOrderState>().single().products

                    val availableStock = getCurrentStock().available

                    val missingProducts = products.filter { availableStock[it.key]?.size ?: 0 < it.value }.keys

                    requireThat {
                        "Command should be of type CreatePurchaseOrderContract.Commands.Create. Got: $currentCommand" using
                                (currentCommand is PurchaseOrderContract.Commands.Create)

                        "Wrong signers. Expected: $expectedSigners, Got: $currentSigners" using
                                (currentSigners == expectedSigners)

                        "Not enough items in stock for products: $missingProducts" using missingProducts.isEmpty()
                    }

                }
            })

            val purchaseOrderState = signedTransaction.tx.outputsOfType<PurchaseOrderState>().single()
            subFlow(ReserveProductFlowInitiator(purchaseOrderState.linearId, purchaseOrderState.products))

            subFlow(ReceiveFinalityFlow(counterpartySession, signedTransaction.id))
        }

        @Suspendable
        private fun getCurrentStock(): Stock {
            return subFlow(GetStockStateAndRefFlow())
                .state
                .data
                .stock
        }
    }

}

