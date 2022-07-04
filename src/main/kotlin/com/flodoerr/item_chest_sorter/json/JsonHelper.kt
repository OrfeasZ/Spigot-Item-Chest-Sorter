package com.flodoerr.item_chest_sorter.json

import com.beust.klaxon.Klaxon
import org.bukkit.command.ConsoleCommandSender
import java.io.File
import java.nio.file.Paths
import kotlin.collections.ArrayList

class JsonHelper(dataFolder: File, commandSender: ConsoleCommandSender? = null, private val performanceMode: Boolean) {

    private val jsonFile = Paths.get(dataFolder.absolutePath, "chests.json").toFile()
    private val doNotTouchFile = Paths.get(dataFolder.absolutePath, "README (don't touch the json file if you don't know what you are doing)").toFile()

    private var cachedJSON: JSON? = null

    init {
        if(!dataFolder.exists()){
            dataFolder.mkdir()
        }
        if(!jsonFile.exists() || jsonFile.readText() == ""){
            jsonFile.writeText(Klaxon().toJsonString(JSON()))
            commandSender?.sendMessage("created json file")
        }
        doNotTouchFile.writeText("Don't touch the json file, if you don't know what you are doing! Really. Don't do it. You may edit the config.yml.")
    }

    /**
     * adds a sender to the json file
     * @param sender sender to be added
     * @return true if added successfully
     *
     * @author Flo Dörr
     */
    fun addSender(sender: Sender): Boolean {
        val json = getJSON()
        for (jsonSender in json.senders) {
            if(jsonSender.id == sender.id){
                return false
            }
        }
        json.senders.add(sender)
        saveJSONIfNecessary(json)
        return true
    }

    /**
     * removes a sender by a sender id
     * @param sid id of sender to be removed
     * @return true if removed successfully
     *
     * @author Flo Dörr
     */
    fun removeSender(sid: String): Boolean {
        val json = getJSON()
        for (sender in json.senders){
            if(sid == sender.id) {
                json.senders.remove(sender)
                saveJSONIfNecessary(json)
                return true
            }
        }
        return false
    }

    /**
     * return a sender object by a sender's id
     * @param sid id of the sender to find
     * @return Sender object if found, else null
     *
     * @author Flo Dörr
     */
    fun getSenderById(sid: String): Sender? {
        val json = getJSON()
        for (sender in json.senders) {
            if(sender.id == sid) {
                return sender
            }
        }
        return null
    }

    /**
     * return a sender object by a sender's cords
     * @param cords coordinates of the sender chest
     * @return Sender object if found, else null
     *
     * @author Flo Dörr
     */
    fun getSenderByCords(cords: Cords): Sender? {
        val json = getJSON()
        for (sender in json.senders) {
            if(sender.cords.left == cords || sender.cords.right == cords) {
                return sender
            }
        }
        return null
    }

    /**
     * return a receiver object by a receiver's cords
     * @param cords coordinates of the receiver chest
     * @return Receiver object if found, else null
     *
     * @author Flo Dörr
     */
    fun getReceiverByCords(cords: Cords): Receiver? {
        val json = getJSON()
        for (receiver in json.receivers) {
            if(receiver.cords.left == cords || receiver.cords.right == cords) {
                return receiver
            }
        }
        return null
    }

    /**
     * checks if a chest was saved by given coordinates
     * @param cords coordinates of a potential chest
     * @return true if found, false if not
     *
     * @author Flo Dörr
     */
    fun chestExists(cords: Cords): Boolean {
        val json = getJSON()

        for (sender in json.senders) {
            if(sender.cords.left == cords || sender.cords.right == cords) {
                return true
            }
        }

        for (receiver in json.receivers) {
            if(receiver.cords.left == cords || receiver.cords.right == cords) {
                return true
            }
        }

        return false
    }

    /**
     * returns all saved sender
     * @return List of sender
     *
     * @author Flo Dörr
     */
    fun getSender(): ArrayList<Sender> {
        return getJSON().senders
    }

    /**
     * adds a receiver to a sender by the sender's id
     * @param receiver to be added
     * @param sid sender id to which the receiver should be added to
     * @return true if added successfully
     *
     * @author Flo Dörr
     */
    fun addReceiver(receiver: Receiver): Boolean {
        val json = getJSON()
        for (jsonReceiver in json.receivers) {
            if(jsonReceiver.id == receiver.id){
                return false
            }
        }
        json.receivers.add(receiver)
        saveJSONIfNecessary(json)
        return true
    }

    /**
     * removes a receiver by a receiver id
     * @param rid id of receiver to be removed
     * @return true if removed successfully
     *
     * @author Flo Dörr
     */
    fun removeReceiver(rid: String): Boolean {
        val json = getJSON()
        for (receiver in json.receivers){
            if(rid == receiver.id) {
                json.receivers.remove(receiver)
                saveJSONIfNecessary(json)
                return true
            }
        }
        return false
    }

    /**
     * return all receiver of a sender by its id
     * @param sid sender's id whose receiver should be returned
     * @return List of receiver
     *
     * @author Flo Dörr
     */
    fun getReceivers(): ArrayList<Receiver> {
        return getJSON().receivers
    }

    /**
     * searches through all chest (senders and receivers) and returns either a Sender or a Receiver
     * @param cords cords of the object to find
     * @return if no chest is found null
     *
     * @author Flo Dörr
     */
    fun getSavedChestFromCords(cords: Cords): Pair<Sender?, Receiver?>? {
        val json = getJSON()

        // first go through sender because it could be potentially faster
        for (sender in json.senders) {
            if (sender.cords.left == cords || sender.cords.right == cords) {
                return Pair(sender, null)
            }
        }

        // if not in sender go through receiver
        for (receiver in json.receivers) {
            if (receiver.cords.left == cords || receiver.cords.right == cords) {
                return Pair(null, receiver)
            }
        }

        return null
    }

    /**
     * reads the json from disk if not already cached
     * @return JSON object
     *
     * @author Flo Dörr
     */
    private fun getJSON(): JSON {
        if(cachedJSON == null) {
            cachedJSON = Klaxon().parse<JSON>(jsonFile.readText())!!
        }
        return cachedJSON as JSON
    }

    /**
     * saves json to drive if not in performance mode
     * @param json JSON object to be potentially saved
     * @return true saved
     *
     * @author Flo Dörr
     */
    private fun saveJSONIfNecessary(json: JSON): Boolean {
        if(performanceMode) {
            return false
        }
        return saveJSON(json)
    }

    /**
     * writes JSON object back to disk
     * @param json JSON object to be saved
     * @return true if saved successfully
     *
     * @author Flo Dörr
     */
    private fun saveJSON(json: JSON): Boolean {
        return try {
            jsonFile.writeText(
                Klaxon().toJsonString(
                    json
                )
            )
            cachedJSON = json
            true
        }catch (exception: Exception) {
            println(exception)
            false
        }
    }

    /**
     * writes JSON object back to disk
     * @return true if saved successfully
     *
     * @author Flo Dörr
     */
    fun saveJSON(): Boolean {
        if (cachedJSON == null) {
            return false
        }
        return saveJSON(cachedJSON!!)
    }
}