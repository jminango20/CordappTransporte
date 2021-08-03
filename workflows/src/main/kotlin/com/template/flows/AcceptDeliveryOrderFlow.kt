package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.DeliveryOrderContract
import com.template.states.DeliveryOrderState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker


object AcceptDeliveryOrderFlow{
    /*
        Parties:
        -Transporter
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

            val transactionBuilder = TransactionBuilder(notary = deliveryOrderStateAndRef.state.notary)

            val newDeliverOrderSate = deliveryOrderStateAndRef.state.data.copy(
                accepted = true
            )

            transactionBuilder
                .addInputState(deliveryOrderStateAndRef)
                .addOutputState(state = newDeliverOrderSate, contract = DeliveryOrderContract::class.qualifiedName!!)
                .addCommand(
                    data = DeliveryOrderContract.Commands.Accept(),
                    keys = listOf(ourIdentity.owningKey)
                )

            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

            val buyerSession = initiateFlow(deliveryOrderStateAndRef.state.data.buyer)
            val sellerSession = initiateFlow(deliveryOrderStateAndRef.state.data.seller)

            deliveryOrderStateAndRef.state.data.buyer

            subFlow(FinalityFlow(signedTransaction, listOf(buyerSession, sellerSession)))
        }
    }

    /*
     Parties:
     -Retail
     -Distributor
     -Producer
  */

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            subFlow(ReceiveFinalityFlow(counterpartySession))
        }

    }
}

