package lib.kasuga.utils.path;

import lombok.Getter;

import java.util.*;
import java.util.function.Function;

public class PathEntryFilterTreeNode {
    @Getter
    private PathFilter filter;

    private final Map<PathFilter, PathEntryFilterTreeNode> children;

    private boolean isTerminal = false;

    public PathEntryFilterTreeNode(PathFilter filter) {
        this.filter = filter;
        children = new HashMap<>();
    }

    public PathEntryFilterTreeNode(PathFilter newFilter, boolean isTerminal, Map<PathFilter, PathEntryFilterTreeNode> children) {
        this.filter = newFilter;
        this.isTerminal = isTerminal;
        this.children = children;
    }

    public void addChild(PathEntryFilterTreeNode child) {
        this.children.put(child.filter, child);
    }

    public Collection<PathEntryFilterTreeNode> getChildren() {
        return children.values();
    }

    public boolean isTreeTerminal() {
        return children.isEmpty();
    }

    public boolean isMarkedTerminal() {
        return isTerminal;
    }

    public void markTerminal() {
        isTerminal = true;
    }

    public void unsetTerminal() {
        isTerminal = false;
    }

    public void addTerminal(PathFilter terminal) {
        for (PathEntryFilterTreeNode child : this.children.values()) {
            child.addTerminal(terminal);
        }
        if(isMarkedTerminal() || this.children.isEmpty()){
            this.unsetTerminal();
            PathEntryFilterTreeNode node = new PathEntryFilterTreeNode(terminal);
            children.put(terminal, node);
            node.markTerminal();
        }
    }

    public void replaceTerminal(Function<PathFilter, PathFilter> terminalReplacement) {
        Set<PathFilter> nodeRemoval = new HashSet<>();
        Map<PathFilter, PathEntryFilterTreeNode> nodeAdded = new HashMap<>();

        for (Map.Entry<PathFilter, PathEntryFilterTreeNode> entry : this.children.entrySet()) {
            if(entry.getValue().isMarkedTerminal() || entry.getValue().isTreeTerminal()) {
                nodeRemoval.add(entry.getKey());
                PathFilter newFilter = terminalReplacement.apply(entry.getKey());
                nodeAdded.put(newFilter, new PathEntryFilterTreeNode(newFilter, this.isTerminal, new HashMap<>(entry.getValue().children)));
            }
        }

        for (PathFilter pathFilter : nodeRemoval) {
            children.remove(pathFilter);
        }

        children.putAll(nodeAdded);

        for (PathEntryFilterTreeNode child : this.children.values()) {
            child.replaceTerminal(terminalReplacement);
        }
    }

    public void add(PathEntryFilter filter) {
        add(filter, 0);
    }

    public void add(PathEntryFilter filter, int start) {
        if(start >= filter.size())
            return;
        PathFilter current = filter.get(start);
        PathEntryFilterTreeNode child = children.computeIfAbsent(current, PathEntryFilterTreeNode::new);
        if(start + 1 == filter.size()) {
            child.markTerminal();
            return;
        }
        child.add(filter, start + 1);
    }
}
