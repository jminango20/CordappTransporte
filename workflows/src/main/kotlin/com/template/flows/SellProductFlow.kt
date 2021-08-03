package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.SaleContract
import com.template.contracts.SaleNotificationContract
import com.template.contracts.StockContract
import com.template.internal.GetStockStateAndRefFlow
import com.template.states.*
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.lang.IllegalArgumentException


object SellProductFlow{

/*
Parties:
    - Retail
*/

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val productType: Product.Type,
        private val price : Int
    ) : FlowLogic<Unit>() {

        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            val stockStateAndRef = subFlow(GetStockStateAndRefFlow())

            val hasNoProduct = stockStateAndRef.state.data.stock.available[productType]?.isEmpty() ?: true
            if(hasNoProduct){
                throw IllegalArgumentException("No products available for type: $productType")
            }

            val notary = serviceHub.networkMapCache.notaryIdentities.first()

            val transactionBuilder = TransactionBuilder(notary = notary)

            val newAvailable = stockStateAndRef.state.data.stock.available.toMutableMap()
            newAvailable[productType] = newAvailable[productType]!!.drop(1)
            val newStockState = stockStateAndRef.state.data.copy(
                stock = stockStateAndRef.state.data.stock.copy(
                    available = newAvailable
                )
            )

            val product = stockStateAndRef.state.data.stock.available[productType]!!.first()

            val newSaleState = SaleState(
                retail = ourIdentity,
                product = product,
                price = price
            )

            transactionBuilder
                .addInputState(stockStateAndRef)
                .addOutputState(state = newStockState, contract = StockContract::class.qualifiedName!!)
                .addOutputState(state = newSaleState, contract = SaleContract::class.qualifiedName!!)
                .addCommand(
                    data = StockContract.Commands.SellProduct(),
                    keys = listOf(ourIdentity.owningKey)
                )


            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)
            subFlow(FinalityFlow(signedTransaction, listOf()))

            notifyProducer(product)
        }

        @Suspendable
        private fun notifyProducer(product: Product){
            initiateFlow(product.producer).send(product) //inicia sesion y envia
        }
    }



    /*
      Parties:
      -Producer
   */
    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val product = counterpartySession.receive<Product>().unwrap { it }

            val notary = serviceHub.networkMapCache.notaryIdentities.first()

            val transactionBuilder = TransactionBuilder(notary = notary)

            val newSaleNotificationState = SaleNotificationState(
                retail = counterpartySession.counterparty,
                product = product
            )

            transactionBuilder
                .addOutputState(
                    state = newSaleNotificationState,
                    contract = SaleNotificationContract::class.qualifiedName!!)
                .addCommand(
                    data = SaleNotificationContract.Commands.Create(),
                    keys = listOf(ourIdentity.owningKey)
                )
            transactionBuilder.verify(serviceHub)

            val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)
            subFlow(FinalityFlow(signedTransaction, listOf()))
        }
    }
}

