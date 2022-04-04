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

import me.TechsCode.UltraPermissions.UltraPermissions;
import me.TechsCode.UltraPermissions.UltraPermissionsAPI;
import me.TechsCode.UltraPermissions.storage.objects.Permission;
import me.TechsCode.UltraPermissions.storage.objects.UserRankup;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;

import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationUltraPermissions extends MigrationJavaPlugin {
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
    }

    @Override
    public void runMigration(CommandSender sender, String[] args) {
        log(sender, "Starting.");

        UltraPermissionsAPI ultraPermsApi = UltraPermissions.getAPI();

        // Migrate all groups
        log(sender, "Starting group migration.");

        int maxWeight = ultraPermsApi.getGroups().stream()
                .mapToInt(g -> g.getPriority())
                .max()
                .orElse(0) + 5;

        AtomicInteger groupCount = new AtomicInteger(0);
        Iterators.tryIterate(ultraPermsApi.getGroups(), group -> {
            String groupName = MigrationUtils.standardizeName(group.getName());
            int weight = maxWeight - group.getPriority();

            Group lpGroup = this.luckPerms.getGroupManager().createAndLoadGroup(groupName).join();
            MigrationUtils.setGroupWeight(lpGroup, weight);
            copy(group, lpGroup, weight);

            for (me.TechsCode.UltraPermissions.storage.objects.Group inherited : group.getActiveInheritedGroups()) {
                String inheritedName = MigrationUtils.standardizeName(inherited.getName());
                InheritanceNode.Builder builder = InheritanceNode.builder(inheritedName);
                inherited.getServer().ifPresent(v -> builder.withContext(DefaultContextKeys.SERVER_KEY, v));
                inherited.getWorld().ifPresent(v -> builder.withContext(DefaultContextKeys.WORLD_KEY, v));
                lpGroup.data().add(builder.build());
            }

            this.luckPerms.getGroupManager().saveGroup(lpGroup);
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        });
        log(sender, "Migrated " + groupCount.get() + " groups");

        // Migrate all users.
        log(sender, "Starting user migration.");

        int userWeight = maxWeight + 5;

        AtomicInteger userCount = new AtomicInteger(0);
        Iterators.tryIterate(ultraPermsApi.getUsers(), user -> {
            User lpUser = this.luckPerms.getUserManager().loadUser(user.getUuid(), user.getName()).join();
            copy(user, lpUser, userWeight);

            for (UserRankup inheritance : user.getRankups()) {
                me.TechsCode.UltraPermissions.storage.objects.Group inherited = inheritance.getGroup().get().orElse(null);
                String inheritedName = MigrationUtils.standardizeName(inherited.getName());
                InheritanceNode.Builder builder = InheritanceNode.builder(inheritedName);
                if (inheritance.getExpiry() != 0) {
                    builder.expiry(Instant.ofEpochMilli(inheritance.getExpiry()));
                }
                inherited.getServer().ifPresent(v -> builder.withContext(DefaultContextKeys.SERVER_KEY, v));
                inherited.getWorld().ifPresent(v -> builder.withContext(DefaultContextKeys.WORLD_KEY, v));
                lpUser.data().add(builder.build());
            }

            if (user.isSuperadmin()) {
                lpUser.data().add(Node.builder("*").build());
                lpUser.data().add(Node.builder("luckperms.*").build());
            }

            this.luckPerms.getUserManager().saveUser(lpUser);
            this.luckPerms.getUserManager().cleanupUser(lpUser);
            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        });

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the UltraPermissions jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
    }

    private static Node toNode(Permission perm) {
        NodeBuilder<?, ?> builder = Node.builder(perm.getName()).value(perm.isPositive());
        if (perm.getExpiration() != 0) {
            builder.expiry(Instant.ofEpochMilli(perm.getExpiration()));
        }
        perm.getServer().ifPresent(v -> builder.withContext(DefaultContextKeys.SERVER_KEY, v));
        perm.getWorld().ifPresent(v -> builder.withContext(DefaultContextKeys.WORLD_KEY, v));
        return builder.build();
    }

    private static void copy(me.TechsCode.UltraPermissions.storage.objects.PermissionHolder holder, PermissionHolder lpHolder, int weight) {
        for (Permission permission : holder.getPermissions()) {
            lpHolder.data().add(toNode(permission));
        }

        holder.getPrefix().ifPresent(prefix -> lpHolder.data().add(PrefixNode.builder(prefix, weight).build()));
        holder.getSuffix().ifPresent(prefix -> lpHolder.data().add(SuffixNode.builder(prefix, weight).build()));
    }

}
