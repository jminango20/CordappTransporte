package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.StockContract
import com.template.internal.GetStockStateAndRefFlow
import com.template.states.Product
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/*
Parties:
    -Producer
    -Distributor
    -Retail
*/

@InitiatingFlow
@StartableByRPC
class AddProductFlowInitiator(
    private val product: Product
) : FlowLogic<Unit>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call() {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val transactionBuilder = TransactionBuilder(notary = notary)

        val stockStateAndRef = subFlow(GetStockStateAndRefFlow())

        val newAvailableStock = stockStateAndRef.state.data.stock.available.toMutableMap()
        newAvailableStock[product.type] = (newAvailableStock[product.type] ?: listOf()) + listOf(product)

        val newStockState = stockStateAndRef.state.data.copy(
            stock = stockStateAndRef.state.data.stock.copy(
                available = newAvailableStock
            )
        )


        transactionBuilder
                .addInputState(stockStateAndRef)
                .addOutputState(state = newStockState, contract = StockContract::class.qualifiedName!!)
                .addCommand(
                    data = StockContract.Commands.AddProduct(),
                    keys = listOf(ourIdentity.owningKey)
                )

        transactionBuilder.verify(serviceHub)

        val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder, listOf(ourIdentity.owningKey))
        subFlow(FinalityFlow(signedTransaction, listOf()))
    }



}