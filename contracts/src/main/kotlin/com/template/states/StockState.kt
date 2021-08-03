package com.template.states

import com.template.contracts.StockContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.util.*

@CordaSerializable
data class Product(
    val id: UUID = UUID.randomUUID(), //diferenciar os produtos
        val producer: Party,
        val type: Type
){
    @CordaSerializable
    enum class Type { A,B,C,D }
}

typealias Inventory = Map<Product.Type, List<Product>>

@CordaSerializable
data class Stock(
    val available: Inventory = emptyMap(),
    val reserved: Map<UniqueIdentifier, Inventory> = emptyMap()
)

@BelongsToContract(StockContract::class)
data class StockState(
        val owner: Party,
        val stock: Stock = Stock(),

        override val participants: List<AbstractParty> = listOf(owner)
) : ContractState