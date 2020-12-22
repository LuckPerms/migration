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

import de.bananaco.bpermissions.api.Calculable;
import de.bananaco.bpermissions.api.CalculableType;
import de.bananaco.bpermissions.api.Permission;
import de.bananaco.bpermissions.api.World;
import de.bananaco.bpermissions.api.WorldManager;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationBPermissions extends JavaPlugin {
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
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

        WorldManager worldManager = WorldManager.getInstance();
        if (worldManager == null) {
            log(sender, "Plugin not loaded.");
            return true;
        }

        log(sender, "Forcing the plugin to load all data. This could take a while.");
        for (World world : worldManager.getAllWorlds()) {
            log(sender, "Loading users in world " + world.getName());

            YamlConfiguration yamlWorldUsers = null;
            try {
                yamlWorldUsers = (YamlConfiguration) UCONFIG_FIELD.get(world);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            if (yamlWorldUsers == null) {
                continue;
            }

            ConfigurationSection configSection = yamlWorldUsers.getConfigurationSection("users");
            if (configSection == null) {
                continue;
            }

            Set<String> users = configSection.getKeys(false);
            if (users == null) {
                log(sender, "Couldn't get a list of users.");
                return true;
            }

            AtomicInteger userLoadCount = new AtomicInteger(0);
            for (String user : users) {
                world.loadOne(user, CalculableType.USER);
                if (userLoadCount.incrementAndGet() % 500 == 0) {
                    log(sender, "Forcefully loaded " + userLoadCount.get() + " users so far.");
                }
            }
        }
        log(sender, "Forcefully loaded all users.");

        // Migrate one world at a time.
        log(sender, "Starting world migration.");
        Iterators.tryIterate(worldManager.getAllWorlds(), world -> {
            log(sender, "Migrating world: " + world.getName());

            // Migrate all groups
            log(sender, "Starting group migration in world " + world.getName() + ".");
            AtomicInteger groupCount = new AtomicInteger(0);

            Iterators.tryIterate(world.getAll(CalculableType.GROUP), group -> {
                String groupName = MigrationUtils.standardizeName(group.getName());
                if (group.getName().equalsIgnoreCase(world.getDefaultGroup())) {
                    groupName = "default";
                }

                // Make a LuckPerms group for the one being migrated.
                Group lpGroup = this.luckPerms.getGroupManager().createAndLoadGroup(groupName).join();

                MigrationUtils.setGroupWeight(lpGroup, group.getPriority());
                migrateHolder(world, group, lpGroup);

                this.luckPerms.getGroupManager().saveGroup(lpGroup);

                log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
            });
            log(sender, "Migrated " + groupCount.get() + " groups in world " + world.getName() + ".");


            // Migrate all users
            log(sender, "Starting user migration in world " + world.getName() + ".");
            AtomicInteger userCount = new AtomicInteger(0);
            Iterators.tryIterate(world.getAll(CalculableType.USER), user -> {
                // There is no mention of UUIDs in the API. I assume that name = uuid. idk?
                UUID uuid = lookupUuid(user.getName());
                if (uuid == null) {
                    return;
                }

                // Make a LuckPerms user for the one being migrated.
                User lpUser = this.luckPerms.getUserManager().loadUser(uuid).join();

                migrateHolder(world, user, lpUser);

                this.luckPerms.getUserManager().saveUser(lpUser);
                this.luckPerms.getUserManager().cleanupUser(lpUser);

                if (userCount.incrementAndGet() % 500 == 0) {
                    log(sender, "Migrated " + userCount.get() + " users so far.");
                }
            });

            log(sender, "Migrated " + userCount.get() + " users in world " + world.getName() + ".");
        });

        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the bPermissions jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
        return true;
    }

    private static final Field UCONFIG_FIELD;
    static {
        try {
            UCONFIG_FIELD = Class.forName("de.bananaco.bpermissions.imp.YamlWorld").getDeclaredField("uconfig");
            UCONFIG_FIELD.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void migrateHolder(World world, Calculable c, PermissionHolder holder) {
        // Migrate the groups permissions in this world
        for (Permission p : c.getPermissions()) {
            if (p.name().isEmpty()) {
                continue;
            }
            holder.data().add(Node.builder(p.name()).value(p.isTrue()).withContext(DefaultContextKeys.SERVER_KEY, "global").withContext(DefaultContextKeys.WORLD_KEY, world.getName()).build());

            // Include any child permissions
            for (Map.Entry<String, Boolean> child : p.getChildren().entrySet()) {
                if (child.getKey().isEmpty()) {
                    continue;
                }

                holder.data().add(Node.builder(child.getKey()).value(child.getValue()).withContext(DefaultContextKeys.SERVER_KEY, "global").withContext(DefaultContextKeys.WORLD_KEY, world.getName()).build());
            }
        }

        // Migrate any inherited groups
        c.getGroups().forEach(parent -> {
            String parentName = MigrationUtils.standardizeName(parent.getName());
            if (parent.getName().equalsIgnoreCase(world.getDefaultGroup())) {
                parentName = "default";
            }

            holder.data().add(InheritanceNode.builder(parentName).value(true).withContext(DefaultContextKeys.SERVER_KEY, "global").withContext(DefaultContextKeys.WORLD_KEY, world.getName()).build());
        });

        // Migrate existing meta
        for (Map.Entry<String, String> meta : c.getMeta().entrySet()) {
            if (meta.getKey().isEmpty() || meta.getValue().isEmpty()) {
                continue;
            }

            if (meta.getKey().equalsIgnoreCase("prefix")) {
                holder.data().add(PrefixNode.builder(meta.getValue(), c.getPriority()).withContext(DefaultContextKeys.WORLD_KEY, world.getName()).build());
                continue;
            }

            if (meta.getKey().equalsIgnoreCase("suffix")) {
                holder.data().add(SuffixNode.builder(meta.getValue(), c.getPriority()).withContext(DefaultContextKeys.WORLD_KEY, world.getName()).build());
                continue;
            }

            holder.data().add(MetaNode.builder(meta.getKey(), meta.getValue()).withContext(DefaultContextKeys.WORLD_KEY, world.getName()).build());
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
