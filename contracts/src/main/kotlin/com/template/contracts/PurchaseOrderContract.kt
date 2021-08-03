package com.template.contracts

import com.template.states.PurchaseOrderState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException

// ************
// * Contract *
// ************
class PurchaseOrderContract : Contract {

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commandsOfType<Commands>().single()

        when(command.value){
            is Commands.Create -> verifyCreate(tx)
            //is Commands.Consume -> verifyCreate(tx)
            is Commands.Consume -> verifyConsume(tx)
            else -> throw IllegalArgumentException("Unrecognized command.")
        }
    }


    private fun verifyCreate(tx:LedgerTransaction){
        val output = tx.outputsOfType<PurchaseOrderState>().single()

        val currentSigners = tx.commandsOfType<Commands>().single().signers.toSet()
        val expectedSigners = output.participants.map{ it.owningKey }.toSet()

        requireThat {
            "Transaction must have 0 inputs" using tx.inputStates.isEmpty()

            "Buyer and seller must be different" using (output.buyer != output.seller)

            "Products list cannot be empty" using output.products.isNotEmpty()

            "Current signers must be the same as participants" using (currentSigners == expectedSigners)
        }

    }

    private fun verifyConsume(tx:LedgerTransaction){
        tx.inputsOfType<PurchaseOrderState>().single()

        requireThat {
            "Outputs should be empty" using tx.outputStates.isEmpty()
        }

    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Create : Commands
        class Consume : Commands
    }
}