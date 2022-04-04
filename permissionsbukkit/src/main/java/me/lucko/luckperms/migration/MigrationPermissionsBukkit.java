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

import com.platymuus.bukkit.permissions.PermissionsPlugin;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationPermissionsBukkit extends MigrationJavaPlugin {
    private LuckPerms luckPerms;
    private PermissionsPlugin permissionsBukkit;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        this.permissionsBukkit = JavaPlugin.getPlugin(PermissionsPlugin.class);
    }

    @Override
    public void runMigration(CommandSender sender, String[] args) {
        log(sender, "Starting.");

        FileConfiguration config = permissionsBukkit.getConfig();

        // Migrate all groups
        log(sender, "Starting group migration.");
        AtomicInteger groupCount = new AtomicInteger(0);

        ConfigurationSection groupsSection = config.getConfigurationSection("groups");

        Iterators.tryIterate(groupsSection.getKeys(false), key -> {
            final String groupName = MigrationUtils.standardizeName(key);
            Group lpGroup = this.luckPerms.getGroupManager().createAndLoadGroup(groupName).join();

            // migrate data
            if (groupsSection.isConfigurationSection(key)) {
                migrate(lpGroup, groupsSection.getConfigurationSection(key));
            }

            this.luckPerms.getGroupManager().saveGroup(lpGroup).join();
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        });
        log(sender, "Migrated " + groupCount.get() + " groups");

        // Migrate all users
        log(sender, "Starting user migration.");
        AtomicInteger userCount = new AtomicInteger(0);

        ConfigurationSection usersSection = config.getConfigurationSection("users");

        Iterators.tryIterate(usersSection.getKeys(false), key -> {
            UUID uuid = lookupUuid(key);
            if (uuid == null) {
                return;
            }

            User lpUser = this.luckPerms.getUserManager().loadUser(uuid).join();

            // migrate data
            if (usersSection.isConfigurationSection(key)) {
                migrate(lpUser, usersSection.getConfigurationSection(key));
            }


            this.luckPerms.getUserManager().saveUser(lpUser);
            this.luckPerms.getUserManager().cleanupUser(lpUser);
            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        });

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the PermissionsBukkit jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
    }

    private static void migrate(PermissionHolder holder, ConfigurationSection data) {
        // migrate permissions
        if (data.isConfigurationSection("permissions")) {
            ConfigurationSection permsSection = data.getConfigurationSection("permissions");
            for (String perm : permsSection.getKeys(false)) {
                boolean value = permsSection.getBoolean(perm);
                holder.data().add(MigrationUtils.parseNode(perm, value).build());
            }
        }

        if (data.isConfigurationSection("worlds")) {
            ConfigurationSection worldSection = data.getConfigurationSection("worlds");
            for (String world : worldSection.getKeys(false)) {
                if (worldSection.isConfigurationSection(world)) {
                    ConfigurationSection permsSection = worldSection.getConfigurationSection(world);
                    for (String perm : permsSection.getKeys(false)) {
                        boolean value = permsSection.getBoolean(perm);
                        holder.data().add(MigrationUtils.parseNode(perm, value).withContext(DefaultContextKeys.WORLD_KEY, world).build());
                    }
                }
            }
        }

        // migrate parents
        if (data.isList("groups")) {
            List<String> groups = data.getStringList("groups");
            for (String group : groups) {
                holder.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(group)).build());
            }
        }
        if (data.isList("inheritance")) {
            List<String> groups = data.getStringList("inheritance");
            for (String group : groups) {
                holder.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(group)).build());
            }
        }
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
