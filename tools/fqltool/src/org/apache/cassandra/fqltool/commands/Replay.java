/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.fqltool.commands;


import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;

import io.airlift.airline.Arguments;
import io.airlift.airline.Command;
import io.airlift.airline.Option;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ChronicleQueueBuilder;

import org.apache.cassandra.fqltool.FQLQuery;
import org.apache.cassandra.fqltool.FQLQueryIterator;
import org.apache.cassandra.fqltool.QueryReplayer;
import org.apache.cassandra.utils.AbstractIterator;
import org.apache.cassandra.utils.MergeIterator;

/**
 * replay the contents of a list of paths containing full query logs
 */
@Command(name = "replay", description = "Replay full query logs")
public class Replay implements Runnable
{
    @Arguments(usage = "<path1> [<path2>...<pathN>]", description = "Paths containing the full query logs to replay.", required = true)
    private List<String> arguments = new ArrayList<>();

    @Option(title = "target", name = {"--target"}, description = "Hosts to replay the logs to, can be repeated to replay to more hosts.")
    private List<String> targetHosts;

    @Option(title = "results", name = { "--results"}, description = "Where to store the results of the queries, this should be a directory. Leave this option out to avoid storing results.")
    private String resultPath;

    @Option(title = "keyspace", name = { "--keyspace"}, description = "Only replay queries against this keyspace and queries without keyspace set.")
    private String keyspace;

    @Option(title = "debug", name = {"--debug"}, description = "Debug mode, print all queries executed.")
    private boolean debug;

    @Option(title = "store_queries", name = {"--store-queries"}, description = "Path to store the queries executed. Stores queries in the same order as the result sets are in the result files. Requires --results")
    private String queryStorePath;

    @Override
    public void run()
    {
        try
        {
            List<File> resultPaths = null;
            if (resultPath != null)
            {
                File basePath = new File(resultPath);
                if (!basePath.exists() || !basePath.isDirectory())
                {
                    System.err.println("The results path (" + basePath + ") should be an existing directory");
                    System.exit(1);
                }
                resultPaths = targetHosts.stream().map(target -> new File(basePath, target)).collect(Collectors.toList());
                resultPaths.forEach(File::mkdir);
            }
            if (targetHosts.size() < 1)
            {
                System.err.println("You need to state at least one --target host to replay the query against");
                System.exit(1);
            }
            replay(keyspace, arguments, targetHosts, resultPaths, queryStorePath, debug);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void replay(String keyspace, List<String> arguments, List<String> targetHosts, List<File> resultPaths, String queryStorePath, boolean debug)
    {
        int readAhead = 200; // how many fql queries should we read in to memory to be able to sort them?
        List<ChronicleQueue> readQueues = null;
        List<FQLQueryIterator> iterators = null;
        List<Predicate<FQLQuery>> filters = new ArrayList<>();

        if (keyspace != null)
            filters.add(fqlQuery -> fqlQuery.keyspace() == null || fqlQuery.keyspace().equals(keyspace));

        try
        {
            readQueues = arguments.stream().map(s -> ChronicleQueueBuilder.single(s).readOnly(true).build()).collect(Collectors.toList());
            iterators = readQueues.stream().map(ChronicleQueue::createTailer).map(tailer -> new FQLQueryIterator(tailer, readAhead)).collect(Collectors.toList());
            try (MergeIterator<FQLQuery, List<FQLQuery>> iter = MergeIterator.get(iterators, FQLQuery::compareTo, new Reducer());
                 QueryReplayer replayer = new QueryReplayer(iter, targetHosts, resultPaths, filters, System.out, queryStorePath, debug))
            {
                replayer.replay();
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (iterators != null)
                iterators.forEach(AbstractIterator::close);
            if (readQueues != null)
                readQueues.forEach(Closeable::close);
        }
    }

    @VisibleForTesting
    public static class Reducer extends MergeIterator.Reducer<FQLQuery, List<FQLQuery>>
    {
        List<FQLQuery> queries = new ArrayList<>();
        public void reduce(int idx, FQLQuery current)
        {
            queries.add(current);
        }

        protected List<FQLQuery> getReduced()
        {
            return queries;
        }
        protected void onKeyChange()
        {
            queries.clear();
        }
    }
}
