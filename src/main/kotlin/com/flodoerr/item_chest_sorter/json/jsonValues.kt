package com.flodoerr.item_chest_sorter.json

data class Cords(var x: Int, var y: Int, var z: Int, var world: String? = null)

data class ChestLocation(var left: Cords, var right: Cords? = null)

data class Sender(var id: String, var name: String, var cords: ChestLocation)

data class Receiver(var id: String, var cords: ChestLocation)

data class JSON(var senders: ArrayList<Sender> = ArrayList(), val receivers: ArrayList<Receiver> = ArrayList())