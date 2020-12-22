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

import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.WeightNode;

public final class MigrationUtils {
    private MigrationUtils() {}

    public static NodeBuilder<?, ?> parseNode(String permission, boolean value) {
        if (permission.length() > 1) {
            if (permission.charAt(0) == '-' || permission.charAt(0) == '!') {
                permission = permission.substring(1);
                value = false;
            } else if (permission.charAt(0) == '+') {
                permission = permission.substring(1);
                value = true;
            }
        }

        return Node.builder(permission).value(value);
    }

    public static void setGroupWeight(Group group, int weight) {
        group.data().clear(NodeType.WEIGHT.predicate());
        group.data().add(WeightNode.builder(weight).build());
    }

    public static String standardizeName(String string) {
        return string.trim()
                .replace(':', '-')
                .replace(' ', '-')
                .replace('.', '-')
                .toLowerCase();
    }

}