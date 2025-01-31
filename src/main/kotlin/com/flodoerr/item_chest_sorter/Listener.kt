package com.flodoerr.item_chest_sorter

import com.flodoerr.item_chest_sorter.animation.animateItem
import com.flodoerr.item_chest_sorter.animation.animating
import com.flodoerr.item_chest_sorter.json.*
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.ShulkerBox
import org.bukkit.command.CommandSender
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import java.util.*
import kotlin.concurrent.schedule


class Listener(private val db: JsonHelper, private val main: ItemChestSorter) : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerInteractEvent(e: PlayerInteractEvent) {
        if (e.item != null && e.item!!.itemMeta != null && e.item?.type == Material.WOODEN_HOE) {
            val item = e.item!!
            val itemMeta = item.itemMeta!!

            val arrowLevel = itemMeta.getEnchantLevel(Enchantment.ARROW_DAMAGE)
            val fireLevel = itemMeta.getEnchantLevel(Enchantment.ARROW_FIRE)

            if (fireLevel != 65535 && arrowLevel != 65535) {
                return
            }

            if (e.clickedBlock == null || e.clickedBlock?.state !is Container) {
                e.isCancelled = true
                return
            }

            if (e.clickedBlock?.state !is ShulkerBox || e.clickedBlock?.state is ShulkerBox && main.config.getBoolean(
                    "allowShulkerBoxes",
                    false
                )
            ) {
                val displayName = itemMeta.displayName

                val block = e.clickedBlock!!

                if (fireLevel == 65535 && displayName == SENDER_HOE_NAME) {
                    e.isCancelled = true
                    handleSenderHoe(e.player, block)
                } else if (arrowLevel == 65535 && displayName == RECEIVER_HOE_NAME) {
                    e.isCancelled = true
                    handleReceiverHoe(e.player, block)
                }
            } else {
                if (main.config.getBoolean("chatMessages.disabledShulkerBoxes", true)) {
                    e.player.sendMessage("${ChatColor.YELLOW}Shulker Boxes are not allowed on this server")
                }
                e.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryCloseEvent(e: InventoryCloseEvent) {
        checkInventory(e.inventory, e.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryMoveItemEvent(e: InventoryMoveItemEvent) {
        if (e.destination.type == InventoryType.CHEST && (e.source.type == InventoryType.HOPPER || e.source.type == InventoryType.CHEST)) {
            if (main.config.getBoolean("sendFromHopperOrSenderNoEmptySlot", false)) {
                for (stack in e.destination.contents) {
                    if (stack == null) {
                        return
                    }
                }
                checkInventory(e.destination, null)
                return
            }
            checkInventory(e.destination, null, e.item)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockBreakEvent(e: BlockBreakEvent) {
        if (!db.chestExists(locationToCords(e.block.location))) {
            return
        }

        e.isCancelled = true

        if (main.config.getBoolean("chatMessages.breakingChests", true)) {
            // some ugly chat message :( ...
            val m1 = TextComponent("This chest is either a sender or a receiver. Remove it using ")
            m1.color = net.md_5.bungee.api.ChatColor.RED
            val m2 = TextComponent("/ics remove sender")
            m2.isItalic = true
            m2.color = net.md_5.bungee.api.ChatColor.GRAY
            m2.isUnderlined = true
            m2.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ics remove sender")
            val m3 = TextComponent(" or ")
            m3.color = net.md_5.bungee.api.ChatColor.RED
            val m4 = TextComponent("/ics remove receiver")
            m4.isItalic = true
            m4.color = net.md_5.bungee.api.ChatColor.GRAY
            m4.isUnderlined = true
            m4.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ics remove receiver")
            val m5 = TextComponent(" before breaking it.")
            m5.color = net.md_5.bungee.api.ChatColor.RED
            m1.addExtra(m2)
            m1.addExtra(m3)
            m1.addExtra(m4)
            m1.addExtra(m5)
            e.player.spigot().sendMessage(m1)
        }
    }

    // some helper vars for the hacky workaround
    private var moving = false
    private var timer: Timer? = null

    /**
     * checks the omitted inventory for a potential transfer of items
     * @param inventory Inventory of the sender chest
     * @param player player who triggered the event
     * @param itemStack only needed when the function is called by onInventoryMoveItemEvent
     *
     * @author Flo Dörr
     */
    private fun checkInventory(inventory: Inventory, player: HumanEntity? = null, itemStack: ItemStack? = null) {
        val location = inventory.location ?: return

        // Only move items if this is a sender chest.
        val sender = db.getSenderByCords(locationToCords(location)) ?: return

        val isShulkerBox = inventory.type == InventoryType.SHULKER_BOX
        val shulkerBoxesAllowed = main.config.getBoolean("allowShulkerBoxes", false)

        if (isShulkerBox && !shulkerBoxesAllowed) {
            player?.sendMessage("${ChatColor.YELLOW}Shulker Boxes are not allowed on this server")
            return
        }

        moving = true

        // get items in chest
        val contents = inventory.contents

        if (contents.isEmpty()) {
            return
        }

        val permission = "ics.use.sender"
        if (player != null && !player.hasPermission(permission)) {
            showNoPermissionMessage(player, permission)
            return
        }

        // loop through all chest slots
        val receivers = db.getReceivers()

        if (receivers.isEmpty()) {
            if (main.config.getBoolean("chatMessages.noReceivers", true)) {
                if (player != null) {
                    // some ugly chat message :( ...
                    val m1 = TextComponent("There are no receivers configured yet. Use the ")
                    m1.color = net.md_5.bungee.api.ChatColor.YELLOW
                    val m2 = TextComponent("/ics add receiver ")
                    m2.isItalic = true
                    m2.color = net.md_5.bungee.api.ChatColor.GRAY
                    m2.isUnderlined = true
                    m2.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ics add receiver")
                    val m3 = TextComponent("command.")
                    m3.color = net.md_5.bungee.api.ChatColor.YELLOW
                    m1.addExtra(m2)
                    m1.addExtra(m3)
                    player.spigot().sendMessage(m1)
                } else {
                    main.server.consoleSender.sendMessage("There are no receivers configured yet")
                }
            }

            return
        }

        val airReceiver = ArrayList<Pair<Container, List<ItemStack>?>>()
        val realReceiver = ArrayList<Pair<Container, List<ItemStack>?>>()

        // overwriting the animate all setting if too many items are about to be sent, as this can
        // potentially crash the server!!
        val overwriteAnimation = if (main.config.getBoolean("animation.animateAll", false)) {
            val items = contents.filter { content -> content != null && !content.type.isAir }
                .sumOf { content ->
                    content?.amount
                        ?: 0
                }
            items >= (64 * 2)
        } else {
            false
        }

        for (receiver in receivers) {
            val leftLocation = cordsToLocation(receiver.cords.left)
            val leftChest = leftLocation.world!!.getBlockAt(leftLocation).state as Container

            // get right chest if cords not null
            val rightChest = if (receiver.cords.right != null) {
                inventory.location!!.world
                val rightLocation = cordsToLocation(receiver.cords.right!!)
                rightLocation.world!!.getBlockAt(rightLocation).state as Container
            } else {
                null
            }

            // get block in item frame on chest
            val blocks = getItemFromItemFrameNearChest(leftChest, rightChest)

            // check if no item frame is placed and give hint to user
            if (blocks != null) {
                val map = Pair(leftChest, blocks)
                if (!blocks.stream().anyMatch { stack -> stack.type.isAir }) {
                    realReceiver.add(map)
                } else {
                    airReceiver.add(map)
                }
            } else {
                if (main.config.getBoolean("chatMessages.noItemFrame", true)) {
                    val message =
                        "${ChatColor.YELLOW}There is a receiver chest which has no item frame on it. Please put an item frame on a receiver chest, containing the target item/block. You can also leave the item frame empty to accept all items which could not be sorted."
                    if (player != null) {
                        player.sendMessage(message)
                    } else {
                        main.server.consoleSender.sendMessage(message)
                    }
                }
            }
        }

        // left over items which cannot be sorted in a chest
        val leftOverContent = ArrayList<ItemStack?>()

        if (realReceiver.size > 0) {
            if (itemStack == null) {
                for (content in contents) {
                    // get an itemstack if item cannot be sorted
                    val stack = handleItems(
                        content,
                        player,
                        inventory,
                        realReceiver,
                        false,
                        overwriteAnimation
                    )

                    if (stack != null) {
                        // add this stack to the leftOverContent List
                        leftOverContent.add(stack)
                    }
                }
            } else {
                val stack = handleItems(
                    itemStack,
                    player,
                    inventory,
                    realReceiver,
                    true,
                    overwriteAnimation
                )

                if (stack != null) {
                    // add this stack to the leftOverContent List
                    leftOverContent.add(stack)
                }
            }
        } else {
            // add all items the leftOverContent if there are no realReceivers
            if (itemStack == null) {
                leftOverContent.addAll(contents)
            } else {
                leftOverContent.add(itemStack)
            }
        }

        // if there are items which could not be sorted and there is at least one air chest (#1)
        if (leftOverContent.size > 0 && airReceiver.size > 0) {
            // sort items into "air chests"
            var sendMessage = false

            leftOverContent.filter { left -> left != null && !left.type.isAir }.forEach { left ->
                sendMessage = true
                handleItems(
                    left,
                    player,
                    inventory,
                    airReceiver,
                    itemStack != null,
                    overwriteAnimation
                )
            }

            if (sendMessage && main.config.getBoolean("chatMessages.sortinToAirChest", true)) {
                // only send a message to the player if there is more than air in the chest
                val message =
                    "${ChatColor.YELLOW}Found item(s) which are/is not specified. Sorting into air chest, if there is enough space..."

                if (player != null) {
                    player.sendMessage(message)
                } else {
                    main.server.consoleSender.sendMessage(message)
                }
            }
        }
    }

    /**
     * handles the items to move them to their desired chest
     * @param content ItemStack in the sender chest
     * @param player player who triggered the event
     * @param inventory Inventory of the sender chest
     * @param receivers receiver HashMap
     * @param workaround toggles the workaround for onInventoryMoveItemEvent
     * @return null or itemstack. If null the item was sorted successfully. If not no chest was found for this item.
     *
     * @author Flo Dörr
     */
    private fun handleItems(
        content: ItemStack?,
        player: HumanEntity?,
        inventory: Inventory,
        receivers: ArrayList<Pair<Container, List<ItemStack>?>>,
        workaround: Boolean = false,
        overwriteAnimation: Boolean
    ): ItemStack? {
        var notFound: ItemStack? = null
        if (content != null && content.amount > 0 && !content.type.isAir) {
            for (receiver in receivers) {
                val blocks = receiver.second
                // First check if block == null or the bloc is air, if that is the case we have found an air chest
                // check if item in chest and item on frame are the same
                // check if we have an item set defined in the config.yml matching the item in the frame and in the sender chest
                if ((blocks == null || blocks.stream()
                        .anyMatch { block -> block.type.isAir }) || blocks.any { block -> block.type == content.type } || main.isItemInSet(
                        content,
                        blocks
                    )
                ) {
                    val leftChest = receiver.first
                    if (leftChest.inventory.type === InventoryType.SHULKER_BOX && !main.config.getBoolean(
                            "allowShulkerBoxes",
                            false
                        )
                    ) {
                        if (main.config.getBoolean("chatMessages.disabledShulkerBoxes", true)) {
                            player?.sendMessage("${ChatColor.YELLOW}Shulker Boxes are not allowed on this server")
                        }
                        continue
                    }
                    // determine whether the chest has enough space to hold the items
                    val spaceResult = checkIfChestHasSpace(content, leftChest)
                    // if the spaceResult and the amount of items are the same, the chest is full with no space
                    // to put a single item. continue to find the next chest, if present.
                    if (spaceResult == content.amount) {
                        continue
                    }
                    // only put the amount of items in the receiver chest it can hold
                    if (spaceResult > 0) {
                        content.amount -= spaceResult
                        if (main.config.getBoolean("chatMessages.fullChest", true)) {
                            val message =
                                "${ChatColor.DARK_GREEN}A chest is about to be full. ${ChatColor.YELLOW}$spaceResult${ChatColor.DARK_GREEN} items will be left over if no other chest with this item is defined."
                            if (player != null) {
                                player.sendMessage(message)
                            } else {
                                main.server.consoleSender.sendMessage(message)
                            }
                        }
                    }
                    // move the items
                    moveItem(content, leftChest, player, inventory, workaround, overwriteAnimation)

                    // if there were leftover items: add leftover items to sender chest and continue to find the next chest, if present.
                    if (spaceResult > 0) {
                        content.amount = spaceResult
                        inventory.addItem(content)
                        continue
                    } else {
                        // if all items fitted in the chest: No need to search for another, breaking...
                        // also there was a chest found for the items, resetting notFound...
                        notFound = null
                        break
                    }
                } else {
                    // so far no chest was found containing this item, remember it by assigning it to notFound
                    notFound = content
                }
            }
        }
        // return notFound can be null or an itemstack
        return notFound
    }

    /**
     * handles the items to move them to their desired chest
     * @param content ItemStack in the sender chest
     * @param leftChest left part of a chest
     * @param player player who triggered the event
     * @param inventory Inventory of the sender chest
     * @param workaround toggles the workaround for onInventoryMoveItemEvent
     *
     * @author Flo Dörr
     */
    private fun moveItem(
        content: ItemStack, leftChest: Container, player: HumanEntity?,
        inventory: Inventory, workaround: Boolean = false, overwriteAnimation: Boolean
    ) {
        // got some weird bugs with the amount... defining this var actually helped :thinking:
        val amount = content.amount
        // add to (left) chest. Do not use blockInventory as its only the leftChests inventory
        // inventory represents the whole potential double chest inventory
        leftChest.inventory.addItem(content)
        if (main.config.getBoolean("chatMessages.sorted", true)) {
            val message = "${ChatColor.GREEN}sorted ${ChatColor.YELLOW}${amount} ${ChatColor.GREEN}${ChatColor.AQUA}${
                getItemName(content)
            } ${ChatColor.GREEN}to your chest"
            if (player != null) {
                player.sendMessage(message)
            } else {
                main.server.consoleSender.sendMessage(message)
            }
        }
        //remove the item from the sender chest
        inventory.removeItem(content)

        if (main.config.getBoolean("sendFromHopperOrSender", false)) {
            // calling InventoryMoveItemEvent as this behavior is very similar to vanilla hoppers, just remote
            // this also enables chaining sender and receiver sets
            Bukkit.getServer().pluginManager.callEvent(
                InventoryMoveItemEvent(
                    inventory,
                    content,
                    leftChest.inventory,
                    true
                )
            )
        }

        if (main.config.getBoolean("animation.enabled", false)) {
            val animationAmount = if (main.config.getBoolean("animation.animateAll", false)) {
                if (overwriteAnimation || animating) {
                    1
                } else {
                    amount
                }
            } else {
                1
            }
            // only animate if chest are in the same world

            if (inventory.location!!.world!!.uid == leftChest.location.world!!.uid) {
                animateItem(content, inventory.location!!, leftChest.location, main, animationAmount)
            }
        }

        // very ugly way of doing it but I could not find another way :(
        // deleting the leftover item in the sender chest when items are put into it by a hopper
        if (workaround) {
            moving = false
            timer?.cancel()
            timer?.purge()
            val inv = inventory.location!!.world!!.getBlockAt(inventory.location!!).state as Container
            timer = Timer("Workaround")
            timer!!.schedule(500) {
                if (!moving) {
                    if (inv is Chest) {
                        inv.blockInventory.filter { item -> content.isSimilar(item) }
                            .forEach { _ -> inv.blockInventory.removeItem(content) }
                    } else {
                        inv.inventory.filter { item -> content.isSimilar(item) }
                            .forEach { _ -> inv.inventory.removeItem(content) }
                    }
                    cancel()
                } else {
                    cancel()
                }
            }
        } else {
            moving = false
        }
    }

    /**
     * gets the name of an item or block
     * @param item ItemStack
     * @return name of ItemStack
     *
     * @author Flo Dörr
     */
    private fun getItemName(item: ItemStack): String {
        // hack the item/block name together...
        return if (item.hasItemMeta()) {
            item.itemMeta!!.displayName
        } else {
            item.type.name.lowercase(Locale.getDefault()).replace("_", " ")
        }
    }

    /**
     * checks if a given chest has enough space to hold a given ItemStack
     * @param item ItemStack you want to add to the chest
     * @param chest chest you want to check
     * @return integer; > 0 = cannot find stack in chest; <=0 stack fits in chest
     *
     * @author Flo Dörr
     */
    private fun checkIfChestHasSpace(item: ItemStack, chest: Container): Int {
        var count = item.amount
        for (stack in chest.inventory.contents) {
            if (stack == null) {
                count -= item.maxStackSize
            } else if (stack.type == item.type) {
                count -= item.maxStackSize - stack.amount
            }
        }
        return count
    }

    /**
     * gets the item frame on a chest using BoundingBox
     * @param leftChest left chest of a (double) chest
     * @param rightChest right chest of a double chest. If this is no double chest. right chest is null
     * @return ItemStack with the item in the item frame. Can be null if no item frame can be found
     */
    private fun getItemFromItemFrameNearChest(leftChest: Container, rightChest: Container?): List<ItemStack>? {
        if (!leftChest.chunk.isLoaded) {
            if (!leftChest.chunk.load()) {
                println("chunk of left chest could not be loaded")
            }
        }

        // get BoundingBox of chest and expand it by .1 to find the item frame
        val box = BoundingBox.of(leftChest.block)
        box.expand(.1)
        val entities = leftChest.world.getNearbyEntities(box)

        val frames = entities.filterIsInstance<ItemFrame>().map { frame -> frame.item }

        if (frames.isNotEmpty()) {
            return frames
        }

        return if (rightChest != null) {
            // frame not found on left sid, check right side recursively...
            getItemFromItemFrameNearChest(rightChest, null)
        } else {
            // no frame on chest
            null
        }
    }

    /**
     * converts Bukkits Location Object into Cords being used in the JSON file
     * @param location Location to be converted
     * @return Cord Object with the x, y and y coordinates of the location omitted
     *
     * @author Flo Dörr
     */
    private fun locationToCords(location: Location): Cords {
        return Cords(location.blockX, location.blockY, location.blockZ, location.world!!.uid.toString())
    }

    /**
     * converts Cords being used in the JSON file into Bukkits Location Object
     * @param cords Cords to be converted
     * @return Location Object with the x, y and y coordinates and World of the Cords Object omitted
     *
     * @author Flo Dörr
     */
    private fun cordsToLocation(cords: Cords): Location {
        return Location(
            Bukkit.getWorld(UUID.fromString(cords.world)),
            cords.x.toDouble(),
            cords.y.toDouble(),
            cords.z.toDouble()
        )
    }

    /**
     * converts an inventory Object to a ChestLocation Object used in the json file
     * @param chest Inventory of a chest
     * @return ChestLocation with both sides of a double chest or the only side of a single chest
     *
     * @author Flo Dörr
     */
    private fun getChestLocation(chest: Inventory): ChestLocation {
        val left: Cords?
        var right: Cords? = null
        if (chest is DoubleChestInventory) {
            left = locationToCords(chest.leftSide.location!!)
            right = locationToCords(chest.rightSide.location!!)
        } else {
            left = locationToCords(chest.location!!)
        }
        return ChestLocation(left, right)
    }

    /**
     * handles the sender hoe behavior
     * @param player Player
     * @param block chest
     *
     * @author Flo Dörr
     */
    private fun handleSenderHoe(player: Player, block: Block) {
        val existingSender = db.getSenderByCords(locationToCords(block.location))

        if (existingSender != null) {
            val permission = "ics.select.sender"
            if (!player.hasPermission(permission)) {
                showNoPermissionMessage(player, permission)
                return
            }

            player.sendMessage("${ChatColor.GREEN}This chest is already a sender.")
            player.inventory.setItemInMainHand(null)
            player.sendMessage("${ChatColor.YELLOW}If you want to remove this chest instead, please click on the next message in the chat (!This also implies the deletion of the receiver chests!):")

            val message = TextComponent("delete")
            message.color = net.md_5.bungee.api.ChatColor.RED
            message.isUnderlined = true
            message.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ics remove sender ${existingSender.id}")
            player.spigot().sendMessage(message)
            return
        }

        val permission = "ics.create.sender"
        if (!player.hasPermission(permission)) {
            showNoPermissionMessage(player, permission)
            return
        }

        val chestLocation = getChestLocation((block.state as Container).inventory)
        var sid = "${chestLocation.left.x}~${chestLocation.left.y}~${chestLocation.left.z}~${
            Bukkit.getServer().getWorld(UUID.fromString(chestLocation.left.world))!!.name
        }~sender"
        sid += if (chestLocation.right != null) {
            "~double~chest"
        } else {
            "~chest"
        }

        val sender = Sender(sid, "Sender", chestLocation)
        db.addSender(sender)

        player.inventory.setItemInMainHand(null)

        player.sendMessage("${ChatColor.GREEN}Successfully saved this chest as sender chest. Because you used the hoe tool the id was auto generated ${ChatColor.GRAY}(${sid})${ChatColor.GREEN}. This chest was also selected as current sender")
    }

    /**
     * handles the receiver hoe behavior
     * @param player Player
     * @param block chest
     *
     * @author Flo Dörr
     */
    private fun handleReceiverHoe(player: Player, block: Block) {
        val chestLocation = getChestLocation((block.state as Container).inventory)

        if (db.getSenderByCords(chestLocation.left) != null) {
            player.sendMessage("${ChatColor.YELLOW}Cannot use a sender chest as a receiver chest.")
            return
        }

        val existingChest = db.getReceiverByCords(chestLocation.left)

        if (existingChest != null) {
            val permission = "ics.remove.receiver"

            if (!player.hasPermission(permission)) {
                showNoPermissionMessage(player, permission)
                return
            }

            // some ugly chat message :( ...
            val m1 = TextComponent("This is already a receiver chest! If you want to delete this chest, ")
            m1.color = net.md_5.bungee.api.ChatColor.RED
            val m2 = TextComponent("click here.")
            m2.color = net.md_5.bungee.api.ChatColor.RED
            m2.isUnderlined = true
            m2.clickEvent = ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/ics remove receiver ${existingChest.id}"
            )
            m1.addExtra(m2)
            player.spigot().sendMessage(m1)

            return
        }

        val permission = "ics.create.receiver"

        if (!player.hasPermission(permission)) {
            showNoPermissionMessage(player, permission)
            return
        }

        var rid = "${chestLocation.left.x}~${chestLocation.left.y}~${chestLocation.left.z}~${
            Bukkit.getServer().getWorld(UUID.fromString(chestLocation.left.world))!!.name
        }~receiver"

        rid += if (chestLocation.right != null) {
            "~double~chest"
        } else {
            "~chest"
        }

        db.addReceiver(
            Receiver(rid, chestLocation)
        )

        player.sendMessage("${ChatColor.GREEN}Successfully saved this chest as a receiver chest. Because you used the hoe tool the id was auto generated (${rid}).")
    }

    /**
     * shows the user a message if he has no permissions
     *
     * @param sender to send the message to
     * @param permission permission the user does not have
     *
     * @author Flo Dörr
     */
    private fun showNoPermissionMessage(sender: CommandSender, permission: String) {
        sender.sendMessage("${ChatColor.RED}You do not have enough permissions to do this (${permission}).")
    }
}
