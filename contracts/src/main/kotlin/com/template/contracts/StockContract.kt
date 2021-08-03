package com.template.contracts

import com.template.states.Product
import com.template.states.SaleState
import com.template.states.StockState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException

class StockContract : Contract {

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commandsOfType<Commands>().single()

        when(command.value){
            is Commands.Create -> verifyCreate(tx)
            is Commands.AddProduct -> verifyAddProduct(tx)
            is Commands.ReserveProducts -> verifyReserveProducts(tx)
            is Commands.RemoveReservedProducts -> verifyRemoveReservedProducts(tx)
            is Commands.SellProduct -> verifySellProduct(tx)
            else -> throw IllegalArgumentException("Unrecognized command.")
        }
    }

    private fun verifyCreate(tx: LedgerTransaction){
        requireThat {
            "Cannot create new StockState if one already exist." using tx.inputsOfType<StockState>().isEmpty()
        }
    }

    private fun verifyAddProduct(tx: LedgerTransaction){
        val input = tx.inputsOfType<StockState>().single()
        val output = tx.outputsOfType<StockState>().single()

        requireThat {
            "Trying to update StockState with the same vale" using (input != output)
        }
    }

    private fun verifyReserveProducts(tx: LedgerTransaction){
        val input = tx.inputsOfType<StockState>().single().stock
        //val output = tx.inputsOfType<StockState>().single().stock
        val output = tx.outputsOfType<StockState>().single().stock

        val reservedID = (output.reserved.keys - input.reserved.keys).single()

        val expectedReservedInput = output.reserved.toMutableMap()
        expectedReservedInput.remove(reservedID)

        input.reserved[reservedID]

        val expectedAvailableInput = output.available.toMutableMap()
        input.reserved[reservedID]?.forEach {
            expectedAvailableInput[it.key] = it.value + (expectedAvailableInput[it.key] ?: listOf())
        }

        requireThat {
            "Only one ID should have been added to reserved input." using (input.reserved == expectedReservedInput)

            "Only reserved products should be removed from available." using
                    (input.available == expectedAvailableInput)
        }
    }

    private fun verifyRemoveReservedProducts(tx: LedgerTransaction){
        val input = tx.inputsOfType<StockState>().single().stock
        val output = tx.outputsOfType<StockState>().single().stock

        val removedID = (input.reserved.keys - output.reserved.keys).single()

        val expectedReserved = input.reserved.toMutableMap()
        expectedReserved.remove(removedID)

        requireThat {
            "Available products should not change. Input: ${input.available}, Output: ${output.available}" using
                    (input.available == output.available)

            "Only one ID should be removed from reserved.\n" +
                    "Current: ${output.reserved}\n" +
                    "Expected: $expectedReserved" using (output.reserved == expectedReserved)
        }
    }


    private fun verifySellProduct(tx: LedgerTransaction){
        val input = tx.inputsOfType<StockState>().single().stock
        val output = tx.outputsOfType<StockState>().single().stock

        val expectedOutput = input.copy(available = output.available)

        val inputProductsAvailable = input.available.flatMap { it.value }.toSet()
        val outputProductsAvailable = output.available.flatMap { it.value }.toSet()

        requireThat {
            "Stock should not change except for available" using
                    (output==expectedOutput)

            "Only one product should be removed from available" using
                    ((inputProductsAvailable - outputProductsAvailable).size == 1)

            "There should be no new output products" using
                    ((outputProductsAvailable-inputProductsAvailable).isEmpty())
        }

        val product = (inputProductsAvailable - outputProductsAvailable).single()

        verifySaleState(tx, product)
    }

    private fun verifySaleState(tx: LedgerTransaction, product:Product){
        val output = tx.outputsOfType<SaleState>().single()

        requireThat {
            "No SaleState should be consumed" using
                    tx.inputsOfType<SaleState>().isEmpty()

            "Price has to be greater than 0" using
                    (output.price>0)

            "SaleState.product should be the product removed from stock" using
                    (output.product == product)
        }

    }


    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Create : Commands
        class  AddProduct : Commands
        class ReserveProducts : Commands
        class RemoveReservedProducts : Commands
        class SellProduct : Commands
    }
}