package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.DeliveryOrderContract
import com.template.states.DeliveryOrderState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object ReceiveDeliveryOrderFlow{

    /*
    Parties:
    -Retail
    - Distributor
 */

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val deliveryOrderStateID: UniqueIdentifier
    ) : FlowLogic<Unit>() {

        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            val deliveryOrderStateAndRef = serviceHub
                .vaultService
                .queryBy<DeliveryOrderState>(
                    QueryCriteria.LinearStateQueryCriteria(linearId = listOf(deliveryOrderStateID))
                )
                .states
                .single()

            receiveDeliveryOrder(deliveryOrderStateAndRef)
            addProductsToStock(deliveryOrderStateAndRef)
        }

        @Suspendable
        private fun receiveDeliveryOrder(deliveryOrderStateAndRef: StateAndRef<DeliveryOrderState>){
            val transactionBuilder = TransactionBuilder(notary = deliveryOrderStateAndRef.state.notary)

            transactionBuilder
                .addInputState(deliveryOrderStateAndRef)
                .addCommand(
                    data = DeliveryOrderContract.Commands.Receive(),
                    keys = listOf(ourIdentity.owningKey)
                )

            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

            val sellerSession = initiateFlow(deliveryOrderStateAndRef.state.data.seller)
            val deliveryCompanySession = initiateFlow(deliveryOrderStateAndRef.state.data.deliverCompany)

            subFlow(FinalityFlow(signedTransaction, listOf(sellerSession, deliveryCompanySession)))
        }

        @Suspendable
        private fun addProductsToStock(deliveryOrderStateAndRef: StateAndRef<DeliveryOrderState>){
            deliveryOrderStateAndRef.state.data.products.forEach { (_, products) ->
                products.forEach { product ->
                    subFlow(AddProductFlowInitiator(product))
                }
            }
        }
    }

    /*
     Parties:
     -Distributor
     -Producer
     -Transporter
  */

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            subFlow(ReceiveFinalityFlow(counterpartySession))
        }

    }



}

