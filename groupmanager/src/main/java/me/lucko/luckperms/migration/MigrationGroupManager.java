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
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;

import org.anjocaido.groupmanager.GlobalGroups;
import org.anjocaido.groupmanager.GroupManager;
import org.anjocaido.groupmanager.dataholder.WorldDataHolder;
import org.anjocaido.groupmanager.dataholder.worlds.WorldsHolder;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MigrationGroupManager extends JavaPlugin {
    private LuckPerms luckPerms;
    private GroupManager gm;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        this.gm = JavaPlugin.getPlugin(GroupManager.class);
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

        final boolean migrateAsGlobal;
        if (args.length >= 1) {
            migrateAsGlobal = Boolean.parseBoolean(args[0]);
        } else {
            migrateAsGlobal = true;
        }

        final Function<String, String> worldMappingFunc = s -> migrateAsGlobal || s == null ? "global" : s;

        if (!getServer().getPluginManager().isPluginEnabled("GroupManager")) {
            log(sender, "Plugin not loaded.");
            return true;
        }

        List<String> worlds = getServer().getWorlds().stream().map(World::getName).map(String::toLowerCase).collect(Collectors.toList());

        // Migrate Global Groups
        log(sender, "Starting global group migration.");
        GlobalGroups gg = GroupManager.getGlobalGroups();

        AtomicInteger globalGroupCount = new AtomicInteger(0);
        Iterators.tryIterate(gg.getGroupList(), g -> {
            String groupName = MigrationUtils.standardizeName(g.getName());
            Group group = this.luckPerms.getGroupManager().createAndLoadGroup(groupName).join();

            for (String node : g.getPermissionList()) {
                if (node.isEmpty()) continue;
                group.data().add(MigrationUtils.parseNode(node, true).build());
            }
            for (String s : g.getInherits()) {
                if (s.isEmpty()) continue;
                group.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(s)).build());
            }

            this.luckPerms.getGroupManager().saveGroup(group);
            log(sender, "Migrated " + globalGroupCount.incrementAndGet() + " groups so far.");
        });
        log(sender, "Migrated " + globalGroupCount.get() + " global groups");

        // Collect data
        Map<UserIdentifier, Set<Node>> users = new HashMap<>();
        Map<UUID, String> primaryGroups = new HashMap<>();
        Map<String, Set<Node>> groups = new HashMap<>();

        WorldsHolder wh = gm.getWorldsHolder();

        // Collect data for all users and groups.
        log(sender, "Collecting user and group data.");
        Iterators.tryIterate(worlds, String::toLowerCase, world -> {
            log(sender, "Querying world " + world);

            WorldDataHolder wdh = wh.getWorldData(world);

            AtomicInteger groupWorldCount = new AtomicInteger(0);
            Iterators.tryIterate(wdh.getGroupList(), group -> {
                String groupName = MigrationUtils.standardizeName(group.getName());

                groups.putIfAbsent(groupName, new HashSet<>());

                for (String node : group.getPermissionList()) {
                    if (node.isEmpty()) continue;
                    groups.get(groupName).add(MigrationUtils.parseNode(node, true).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                }
                for (String s : group.getInherits()) {
                    if (s.isEmpty()) continue;
                    groups.get(groupName).add(InheritanceNode.builder(MigrationUtils.standardizeName(s)).value(true).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                }

                String[] metaKeys = group.getVariables().getVarKeyList();
                for (String key : metaKeys) {
                    String value = group.getVariables().getVarString(key);
                    key = key.toLowerCase();
                    if (key.isEmpty() || value.isEmpty()) continue;
                    if (key.equals("build")) continue;

                    if (key.equals("prefix")) {
                        groups.get(groupName).add(PrefixNode.builder(value, 50).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                    } else if (key.equals("suffix")) {
                        groups.get(groupName).add(SuffixNode.builder(value, 50).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                    } else {
                        groups.get(groupName).add(MetaNode.builder(key, value).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                    }
                }

                log(sender, "Migrated " + groupWorldCount.incrementAndGet() + " groups so far in world " + world);
            });
            log(sender, "Migrated " + groupWorldCount.get() + " groups in world " + world);

            AtomicInteger userWorldCount = new AtomicInteger(0);
            Iterators.tryIterate(wdh.getUserList(), user -> {
                UUID uuid = lookupUuid(user.getUUID());
                if (uuid == null) {
                    return;
                }

                String lastName = user.getLastName();
                if (lastName != null && Uuids.parse(lastName) != null) {
                    lastName = null;
                }

                UserIdentifier id = new UserIdentifier(uuid, lastName);

                users.putIfAbsent(id, new HashSet<>());

                for (String node : user.getPermissionList()) {
                    if (node.isEmpty()) continue;
                    users.get(id).add(MigrationUtils.parseNode(node, true).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                }

                // Collect sub groups
                String finalWorld = worldMappingFunc.apply(world);
                users.get(id).addAll(user.subGroupListStringCopy().stream()
                        .filter(n -> !n.isEmpty())
                        .map(MigrationUtils::standardizeName)
                        .map(n -> InheritanceNode.builder(n).value(true).withContext(DefaultContextKeys.WORLD_KEY, finalWorld).build())
                        .collect(Collectors.toSet())
                );

                // Get primary group
                primaryGroups.put(uuid, MigrationUtils.standardizeName(user.getGroupName()));

                String[] metaKeys = user.getVariables().getVarKeyList();
                for (String key : metaKeys) {
                    String value = user.getVariables().getVarString(key);
                    key = key.toLowerCase();
                    if (key.isEmpty() || value.isEmpty()) continue;
                    if (key.equals("build")) continue;

                    if (key.equals("prefix")) {
                        users.get(id).add(PrefixNode.builder(value, 100).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                    } else if (key.equals("suffix")) {
                        users.get(id).add(SuffixNode.builder(value, 100).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                    } else {
                        users.get(id).add(MetaNode.builder(key, value).withContext(DefaultContextKeys.WORLD_KEY, worldMappingFunc.apply(world)).build());
                    }
                }

                if (userWorldCount.incrementAndGet() % 500 == 0) {
                    log(sender, "Migrated " + userWorldCount.get() + " users so far in world " + world);
                }
            });
            log(sender, "Migrated " + userWorldCount.get() + " users in world " + world);
        });

        log(sender, "All data has now been processed, now starting the import process.");
        log(sender, "Found a total of " + users.size() + " users and " + groups.size() + " groups.");

        log(sender, "Starting group migration.");
        AtomicInteger groupCount = new AtomicInteger(0);
        Iterators.tryIterate(groups.entrySet(), e -> {
            Group group = this.luckPerms.getGroupManager().createAndLoadGroup(e.getKey()).join();

            for (Node node : e.getValue()) {
                group.data().add(node);
            }

            this.luckPerms.getGroupManager().saveGroup(group);
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        });
        log(sender, "Migrated " + groupCount.get() + " groups");

        log(sender, "Starting user migration.");
        AtomicInteger userCount = new AtomicInteger(0);
        Iterators.tryIterate(users.entrySet(), e -> {
            User user = this.luckPerms.getUserManager().loadUser(e.getKey().uuid, e.getKey().name).join();

            for (Node node : e.getValue()) {
                user.data().add(node);
            }

            String primaryGroup = primaryGroups.get(e.getKey().uuid);
            if (primaryGroup != null && !primaryGroup.isEmpty()) {
                user.data().add(InheritanceNode.builder(primaryGroup).build());
                user.setPrimaryGroup(primaryGroup);
                user.data().remove(InheritanceNode.builder("default").build());
            }

            this.luckPerms.getUserManager().saveUser(user);
            this.luckPerms.getUserManager().cleanupUser(user);
            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        });

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the GroupManager jar from your plugins folder & restart the server. " +
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

    private static final class UserIdentifier {
        final UUID uuid;
        final String name;

        UserIdentifier(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }
}
