package com.template.internal

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.StockContract
import com.template.states.Product
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

@InitiatingFlow
@StartableByRPC
class ReserveProductFlowInitiator(
    private  val purchaseOrderStateID: UniqueIdentifier,
    private val products: Map<Product.Type,Int>
) : FlowLogic<Unit>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call() {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val transactionBuilder = TransactionBuilder(notary = notary)

        val stockStateAndRef = subFlow(GetStockStateAndRefFlow())

        val newAvailableStock = stockStateAndRef.state.data.stock.available.map {
            Pair(it.key, it.value.drop(products[it.key] ?:0))
        }.toMap()


        val newReservedStock = stockStateAndRef.state.data.stock.reserved.toMutableMap()
        newReservedStock[purchaseOrderStateID] = products.map {
            Pair(it.key, stockStateAndRef.state.data.stock.available[it.key]!!.take(it.value))
        }.toMap()

        val newStockState = stockStateAndRef.state.data.copy(
            stock = stockStateAndRef.state.data.stock.copy(
                available = newAvailableStock,
                reserved = newReservedStock
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