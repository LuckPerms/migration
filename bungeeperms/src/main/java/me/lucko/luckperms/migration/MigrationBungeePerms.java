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

import net.alpenblock.bungeeperms.BungeePerms;
import net.alpenblock.bungeeperms.Group;
import net.alpenblock.bungeeperms.PermEntity;
import net.alpenblock.bungeeperms.Server;
import net.alpenblock.bungeeperms.World;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationBungeePerms extends Plugin {
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        this.luckPerms = LuckPermsProvider.get();
        getProxy().getPluginManager().registerCommand(this, new MigrationCommand());
    }

    private void log(CommandSender sender, String msg) {
        getLogger().info(msg);

        if (sender instanceof ProxiedPlayer) {
            sender.sendMessage(new TextComponent("[migration] " + msg));
        }
    }

    private final class MigrationCommand extends Command {
        MigrationCommand() {
            super("migrate-bungeeperms", "luckperms.migration");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            log(sender, "Starting.");

            // Get BungeePerms instance
            BungeePerms bp = BungeePerms.getInstance();
            if (bp == null) {
                log(sender, "Plugin not loaded.");
                return;
            }

            List<Group> groups = bp.getPermissionsManager().getBackEnd().loadGroups();

            log(sender, "Calculating group weightings.");
            int i = 0;
            for (Group group : groups) {
                i = Math.max(i, group.getRank());
            }
            int maxWeight = i + 5;

            // Migrate all groups.
            log(sender, "Starting group migration.");
            AtomicInteger groupCount = new AtomicInteger(0);
            Iterators.tryIterate(groups, g -> {
                int groupWeight = maxWeight - g.getRank();

                // Make a LuckPerms group for the one being migrated
                String groupName = MigrationUtils.standardizeName(g.getName());
                net.luckperms.api.model.group.Group group = luckPerms.getGroupManager().createAndLoadGroup(groupName).join();

                MigrationUtils.setGroupWeight(group, groupWeight);
                migrateHolder(g, g.getInheritances(), groupWeight, group);

                luckPerms.getGroupManager().saveGroup(group);
                log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
            });
            log(sender, "Migrated " + groupCount.get() + " groups");

            // Migrate all users.
            log(sender, "Starting user migration.");
            AtomicInteger userCount = new AtomicInteger(0);

            // Increment the max weight from the group migrations. All user meta should override.
            int userWeight = maxWeight + 5;

            Iterators.tryIterate(bp.getPermissionsManager().getBackEnd().loadUsers(), u -> {
                if (u.getUUID() == null) {
                    log(sender, "Could not parse UUID for user: " + u.getName());
                    return;
                }

                // Make a LuckPerms user for the one being migrated.
                net.luckperms.api.model.user.User user = luckPerms.getUserManager().loadUser(u.getUUID(), u.getName()).join();

                migrateHolder(u, u.getGroupsString(), userWeight, user);

                luckPerms.getUserManager().saveUser(user);
                luckPerms.getUserManager().cleanupUser(user);
                if (userCount.incrementAndGet() % 500 == 0) {
                    log(sender, "Migrated " + userCount.get() + " users so far.");
                }
            });

            log(sender, "Migrated " + userCount.get() + " users.");
            log(sender, "Success! Migration complete.");
            log(sender, "Don't forget to remove the BungeePerms jar from your plugins folder & restart the server. " +
                    "LuckPerms may not take over as the server permission handler until this is done.");
        }
    }

    private static void migrateHolder(PermEntity entity, List<String> parents, int weight, PermissionHolder holder) {
        // Migrate global perms
        for (String perm : entity.getPerms()) {
            if (perm.isEmpty()) continue;
            holder.data().add(MigrationUtils.parseNode(perm, true).build());
        }

        // Migrate per-server perms
        for (Map.Entry<String, Server> e : entity.getServers().entrySet()) {
            for (String perm : e.getValue().getPerms()) {
                if (perm.isEmpty()) continue;
                holder.data().add(MigrationUtils.parseNode(perm, true).withContext(DefaultContextKeys.SERVER_KEY, e.getKey()).build());
            }

            // Migrate per-world perms
            for (Map.Entry<String, World> we : e.getValue().getWorlds().entrySet()) {
                for (String perm : we.getValue().getPerms()) {
                    if (perm.isEmpty()) continue;
                    holder.data().add(MigrationUtils.parseNode(perm, true).withContext(DefaultContextKeys.SERVER_KEY, e.getKey()).withContext(DefaultContextKeys.WORLD_KEY, we.getKey()).build());
                }
            }
        }

        // Migrate any parent groups
        for (String inherit : parents) {
            if (inherit.isEmpty()) continue;
            holder.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(inherit)).build());
        }

        // Migrate prefix and suffix
        String prefix = entity.getPrefix();
        String suffix = entity.getSuffix();

        if (prefix != null && !prefix.isEmpty()) {
            holder.data().add(PrefixNode.builder(prefix, weight).build());
        }
        if (suffix != null && !suffix.isEmpty()) {
            holder.data().add(SuffixNode.builder(suffix, weight).build());
        }
    }
}
