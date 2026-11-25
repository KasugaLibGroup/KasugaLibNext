package lib.kasuga.utils.path;

import lombok.Setter;

import java.util.*;
import java.util.function.Function;

public class PathMatcher {
    @Setter
    PathEntryFilterTreeNode root;
    @Setter
    private boolean noExplore = false;

    public PathMatcher(PathEntryFilterTreeNode root) {
        this.root = root;
    }

    public Collection<PathEntry> match(
            Function<PathEntry, Collection<PathEntry>> listFunction,
            PathEntry initialPath
    ) {
        // System.out.println("------------------------------");
        Collection<PathEntry> list = createUniqueContainer();
        match(list, listFunction, root, initialPath, 0, PathFilter.Result.INITIAL);
        return list;
    }

    protected <T> Collection<T> createUniqueContainer() {
        return new HashSet<>();
    }

    /* Match with Discovery mode, which means we do not know the all directories, and we should list it one by one */
    protected void match(
            Collection<PathEntry> entries,
            Function<PathEntry, Collection<PathEntry>> listFunction,
            PathEntryFilterTreeNode node,
            PathEntry path,
            int pathIndex,
            PathFilter.Result lastResult
    ) {
        if (pathIndex >= path.size()) {
            return;
        }
        if(this.noExplore && entries.contains(path)) {
            /* If the path are already contains in the entries and noExplore is true, then pruning */
            return;
        }

        PathFilter filter = node.getFilter();
        String pathPart = path.get(pathIndex);

        PathFilter.Result testResult = filter.test(pathPart);

        /* If the path do not satisfy the condition, then return now */
        if(testResult == PathFilter.Result.REJECT)
            return;

        if((node.isTreeTerminal()  && testResult != PathFilter.Result.RECURSIVE_ACCEPT) || testResult == PathFilter.Result.RETURN) {
            /* If a node is a tree terminal(node is a terminal but has no children) or the result is RETURN, accept then return */
            entries.add(path);
            return;
        }else if(node.isMarkedTerminal() || node.isTreeTerminal()) {
            /* If a node is marked terminal(node is a terminal but has children), accept then continue */
            entries.add(path);

            /* Because we matched the path, there is no subdirectories, so return directly  */
            if(this.noExplore)
                return;
        }

        /* In the branch here, we are sure to explore the children, and filter by the recursive calls */
        Collection<PathEntry> children = listFunction.apply(path);

        if(testResult == PathFilter.Result.RECURSIVE_ACCEPT && lastResult != PathFilter.Result.RECURSIVE_ACCEPT) {
            /* If we are RECURSIVE_ACCEPT a folder, and the parent is not RECURSIVE_ACCEPT, we try to match the next node in current directories */
            /* E.g. Matcher: **\*.txt -> [\1.txt] */
            /* In the example above, the matcher will first step the tree from "**" to "*.txt" */
            /* Will make a match call: check(1.txt,*.txt) */
            for (PathEntryFilterTreeNode child : node.getChildren()) {
                match(
                        entries,
                        listFunction,
                        child,
                        path,
                        pathIndex,
                        testResult
                );
                /* Check if the path already it was listed into the entries */
                if(this.noExplore && entries.contains(path))
                    return;
            }
        }

        boolean addedSelf = false;

        for (PathEntry child : children) {
            /* Then, check the matcher for children */
            /* E.g. Matcher: a\*.txt -> [\a\1.txt] */
            /* In the example above, the matcher will step both tree node (a->*.txt) and path(a->1.txt) */
            /* Will make a match call: check(1.txt,*.txt) */
            for (PathEntryFilterTreeNode childNode : node.getChildren()) {
                if(!addedSelf && childNode.getFilter().willMatchSelf() && (childNode.isTreeTerminal() || childNode.isMarkedTerminal())) {
                    entries.add(path);
                    addedSelf = true;
                }
                match(
                        entries,
                        listFunction,
                        childNode,
                        child,
                        /* If we are in a INITIAL node, it will always accept and list, so we should */
                        testResult == PathFilter.Result.INITIAL ? pathIndex : pathIndex + 1,
                        testResult
                );

                // @TODO: Is it necessary to check the noExplore flag here(because we are checking the children?)
//                if(this.noExplore && entries.contains(path))
//                    return;
            }
        }

        if(testResult == PathFilter.Result.RECURSIVE_ACCEPT) {
            /* Check the recursive (**) in the last */
            /* E.g. **\1.txt -> [\a\b\1.txt] */
            /* It will step the path and retain the recursive matcher (a->b), (**->**) */
            /* Will make a match call: check(b\1.txt,**) */
            for(PathEntry child: children) {
                match(
                        entries,
                        listFunction,
                        node,
                        child,
                        pathIndex + 1,
                        testResult
                );

                if(this.noExplore && entries.contains(path))
                    return;
            }
        }
    }

    public Collection<PathEntry> matchParallel(
            Collection<PathEntry> entries,
            PathEntryFilterTreeNode node
    ) {
        // System.out.println("------------------------------");
        Collection<PathEntry> collected = createUniqueContainer();
        Collection<PathEntry> ignored = createUniqueContainer();
        matchParallel(
                new PathEntry(),
                collected,
                entries,
                ignored,
                node,
                0,
                PathFilter.Result.INITIAL
        );
        return collected;
    }

    public void matchParallel(
            PathEntry parent,
            Collection<PathEntry> matched,
            Collection<PathEntry> entries,
            Collection<PathEntry> ignored,
            PathEntryFilterTreeNode node,
            int entryIndex,
            PathFilter.Result lastTest
    ) {
        HashMap<String, Collection<PathEntry>> fullMatchEntryMap = new HashMap<>(entries.size() >> 1);
        HashMap<String, Collection<PathEntry>> entryMap = new HashMap<>(entries.size() >> 1);
        PathFilter filter = node.getFilter();

        for (PathEntry entry : entries) {
            if(entryIndex >= entry.size())
                continue;
            if(ignored.contains(entry))
                continue;
            String key = entry.get(entryIndex);
            if(!entryMap.containsKey(key)) {
                entryMap.put(key, createUniqueContainer());
            }
            entryMap.get(key).add(entry);
            if(entryIndex == entry.size() - 1) {
                if(!fullMatchEntryMap.containsKey(key)) {
                    fullMatchEntryMap.put(key, createUniqueContainer());
                }
                fullMatchEntryMap.get(key).add(entry);
            }
        }

        if(entries.isEmpty())
            return;

        for (String part : entryMap.keySet()) {
            PathFilter.Result testResult = filter.test(part);
            // System.out.println("Matching " + part + " against " + filter + ", result = " + testResult);
            if(testResult == PathFilter.Result.REJECT)
                continue;
            if(node.isMarkedTerminal()) {
                matched.addAll(fullMatchEntryMap.get(part));
                if(node.getFilter().willMatchSelf() && !matched.contains(parent)) {
                    matched.add(parent);
                }
                if(noExplore) {
                    ignored.addAll(entryMap.get(part));
                    return;
                }
            }else if((node.isTreeTerminal() || testResult == PathFilter.Result.RETURN) && (testResult != PathFilter.Result.RECURSIVE_ACCEPT)) {
                matched.addAll(fullMatchEntryMap.get(part));
                if(node.getFilter().willMatchSelf() && !matched.contains(parent)) {
                    matched.add(parent);
                }
                if(noExplore) {
                    ignored.addAll(entryMap.get(part));
                    return;
                }
                continue;
            }
            Collection<PathEntry> childEntries = entryMap.get(part);
            PathEntry current = parent.extend(part);

            for (PathEntryFilterTreeNode childNode : node.getChildren()) {
                if(testResult == PathFilter.Result.RECURSIVE_ACCEPT && lastTest != PathFilter.Result.RECURSIVE_ACCEPT) {
                    matchParallel(
                            current,
                            matched,
                            childEntries,
                            ignored,
                            childNode,
                            entryIndex,
                            testResult
                    );
                }
            }

            for (PathEntryFilterTreeNode childNode : node.getChildren()) {
                matchParallel(
                        testResult == PathFilter.Result.INITIAL ? parent : current,
                        matched,
                        childEntries,
                        ignored,
                        childNode,
                        testResult == PathFilter.Result.INITIAL ? entryIndex : entryIndex + 1,
                        testResult
                );
            }

            if(testResult == PathFilter.Result.RECURSIVE_ACCEPT) {
                matchParallel(
                        current,
                        matched,
                        childEntries,
                        ignored,
                        node,
                        entryIndex + 1,
                        testResult
                );
            }
        }
    }
}
