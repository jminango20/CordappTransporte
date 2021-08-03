package com.template.contracts

import com.template.states.DeliveryOrderState
import com.template.states.PurchaseOrderState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException

// ************
// * Contract *
// ************
class DeliveryOrderContract : Contract {

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commandsOfType<Commands>().single()

        when(command.value){
            is Commands.Create -> verifyCreate(tx)
            is Commands.Accept -> verifyAccept(tx)
            is Commands.Receive -> verifyReceive(tx)
            //is Commands.Accept -> verifyReceive(tx)
            else -> throw IllegalArgumentException("Unrecognized command.")
        }
    }


    private fun verifyCreate(tx: LedgerTransaction){
        val output = tx.outputsOfType<DeliveryOrderState>().single()

        val currentSigners = tx.commandsOfType<Commands>().single().signers.toSet()
        val expectedSigners = setOf(output.seller.owningKey, output.deliverCompany.owningKey)

        requireThat {
            "Accepted should be false" using !output.accepted

            "There should be 3 distinct participants" using (output.participants.toSet().size == 3)

            "Seller and Delivery company should sign" using (currentSigners == expectedSigners)
        }
    }


    private fun verifyAccept(tx: LedgerTransaction){
        val input = tx.inputsOfType<DeliveryOrderState>().single()
        val output = tx.outputsOfType<DeliveryOrderState>().single()

        val expectedOutput = input.copy(accepted = true)

        val currentSigner = tx.commandsOfType<Commands>().single().signers.single()

        requireThat {
            "Input.accepted should be false" using !input.accepted

            "Only accepted property should change" using (output == expectedOutput)

            "Only delivery company should sign" using (currentSigner == output.deliverCompany.owningKey)
        }
    }

    private fun verifyReceive(tx: LedgerTransaction){
        val input = tx.inputsOfType<DeliveryOrderState>().single()

        val currentSigner = tx.commandsOfType<Commands>().single().signers.single()

        requireThat {
            "Input.accepted should be true" using input.accepted

            "There should be no outputs" using (tx.outputsOfType<DeliveryOrderState>().isEmpty())

            "Only buyer should sign" using (currentSigner == input.buyer.owningKey)
        }
    }


    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Create : Commands
        class Accept : Commands
        class  Receive: Commands
    }
}