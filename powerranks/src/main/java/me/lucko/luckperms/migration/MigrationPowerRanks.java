/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.migration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

import nl.svenar.PowerRanks.Cache.CachedPlayers;
import nl.svenar.PowerRanks.Cache.PowerConfigurationSection;
import nl.svenar.PowerRanks.Data.Users;
import nl.svenar.PowerRanks.PowerRanks;
import nl.svenar.PowerRanks.api.PowerRanksAPI;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationPowerRanks extends JavaPlugin {
    private LuckPerms luckPerms;
    private PowerRanks pr;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        this.pr = JavaPlugin.getPlugin(PowerRanks.class);
    }

    private void log(CommandSender sender, String msg) {
        getLogger().info(msg);
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("[migration] " + msg);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        log(sender, "Starting.");

        PowerRanksAPI prApi = pr.loadAPI();
        Users prUsers = new Users(pr);

        // Migrate all groups
        log(sender, "Starting groups migration.");
        Set<String> ranks = prApi.getRanks();
        AtomicInteger groupCount = new AtomicInteger(0);
        for (String rank : ranks) {
            Group group = this.luckPerms.getGroupManager().createAndLoadGroup(rank).join();

            for (String node : prApi.getPermissions(rank)) {
                if (node.isEmpty()) continue;
                group.data().add(MigrationUtils.parseNode(node, true).build());
            }

            for (String parent : prApi.getInheritances(rank)) {
                if (parent.isEmpty()) continue;
                group.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(parent)).build());
            }

            this.luckPerms.getGroupManager().saveGroup(group);
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        }
        log(sender, "Migrated " + groupCount.get() + " groups.");

        // Migrate all users
        log(sender, "Starting user migration.");
        Set<String> playerUuids = prUsers.getCachedPlayers();
        AtomicInteger userCount = new AtomicInteger(0);
        for (String uuidString : playerUuids) {
            UUID uuid = lookupUuid(uuidString);
            if (uuid == null) {
                continue;
            }

            User user = this.luckPerms.getUserManager().loadUser(uuid, null).join();

            user.data().add(InheritanceNode.builder(CachedPlayers.getString("players." + uuidString + ".rank")).build());

            final PowerConfigurationSection subGroups = CachedPlayers.getConfigurationSection("players." + uuidString + ".subranks");
            if (subGroups != null) {
                for (String subGroup : subGroups.getKeys(false)) {
                    InheritanceNode.Builder builder = InheritanceNode.builder(subGroup);
                    for (String worldName : CachedPlayers.getStringList("players." + uuidString + ".subranks." + subGroup + ".worlds")) {
                        if (!worldName.equalsIgnoreCase("all")) {
                            builder.withContext(DefaultContextKeys.WORLD_KEY, worldName);
                        }
                    }
                    user.data().add(builder.build());
                }
            }

            for (String node : CachedPlayers.getStringList("players." + uuidString + ".permissions")) {
                if (node.isEmpty()) continue;
                user.data().add(MigrationUtils.parseNode(node, true).build());
            }

            user.setPrimaryGroup(CachedPlayers.getString("players." + uuidString + ".rank"));

            this.luckPerms.getUserManager().cleanupUser(user);
            this.luckPerms.getUserManager().saveUser(user);
            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        }

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the PowerRanks jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
        return true;
    }

    public UUID lookupUuid(String s) {
        UUID uuid = Uuids.parse(s);
        if (uuid == null) {
            try {
                //noinspection deprecation
                uuid = getServer().getOfflinePlayer(s).getUniqueId();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        if (uuid == null) {
            getLogger().warning("Unable to get a UUID for user identifier: " + s);
        }
        return uuid;
    }

}
