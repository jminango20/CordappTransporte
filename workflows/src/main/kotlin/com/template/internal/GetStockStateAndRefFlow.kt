package com.template.internal

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.StockContract
import com.template.states.StockState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

@InitiatingFlow
@StartableByRPC
class GetStockStateAndRefFlow : FlowLogic<StateAndRef<StockState>>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): StateAndRef<StockState> {
        val currentStockStateAndRef = getCurrentStockStateAndRef()
        if(currentStockStateAndRef != null){
            return currentStockStateAndRef
        }

        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val transactionBuilder = TransactionBuilder(notary = notary)

        transactionBuilder
            .addOutputState(state = StockState(ourIdentity), contract = StockContract::class.qualifiedName!!)
            .addCommand(
                data = StockContract.Commands.Create(),
                keys = listOf(ourIdentity.owningKey)
            )

        transactionBuilder.verify(serviceHub)

        val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder, listOf(ourIdentity.owningKey))
        subFlow(FinalityFlow(signedTransaction, listOf()))

        return  getCurrentStockStateAndRef()!!

    }

    @Suspendable
    private fun getCurrentStockStateAndRef(): StateAndRef<StockState>? {
        return serviceHub
            .vaultService
            .queryBy<StockState>()
            .states
            //.queryBy(StockState::class.java).states
            .firstOrNull { it.state.data.owner == ourIdentity}
    }
}