/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.indexing.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListenableFuture;
import com.metamx.common.ISE;
import com.metamx.emitter.EmittingLogger;
import com.metamx.emitter.service.ServiceEmitter;
import io.druid.common.guava.DSuppliers;
import io.druid.curator.PotentiallyGzippedCompressionProvider;
import io.druid.curator.cache.SimplePathChildrenCacheFactory;
import io.druid.indexing.TestTask;
import io.druid.indexing.common.TaskStatus;
import io.druid.indexing.common.TaskToolboxFactory;
import io.druid.indexing.common.config.TaskConfig;
import io.druid.indexing.common.task.Task;
import io.druid.indexing.common.task.TaskResource;
import io.druid.indexing.coordinator.config.RemoteTaskRunnerConfig;
import io.druid.indexing.coordinator.setup.WorkerSetupData;
import io.druid.indexing.worker.Worker;
import io.druid.indexing.worker.WorkerCuratorCoordinator;
import io.druid.indexing.worker.WorkerTaskMonitor;
import io.druid.indexing.worker.config.WorkerConfig;
import io.druid.jackson.DefaultObjectMapper;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.server.initialization.initialization.ZkPathsConfig;
import io.druid.timeline.DataSegment;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingCluster;
import org.apache.zookeeper.CreateMode;
import org.easymock.EasyMock;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Several of the tests here are integration tests rather than unit tests. We will introduce real unit tests for this
 * class as well as integration tests in the very near future.
 */
public class RemoteTaskRunnerTest
{
  private static final ObjectMapper jsonMapper = new DefaultObjectMapper();
  private static final Joiner joiner = Joiner.on("/");
  private static final String basePath = "/test/druid";
  private static final String announcementsPath = String.format("%s/indexer/announcements/worker", basePath);
  private static final String tasksPath = String.format("%s/indexer/tasks/worker", basePath);
  private static final String statusPath = String.format("%s/indexer/status/worker", basePath);

  private TestingCluster testingCluster;
  private CuratorFramework cf;
  private RemoteTaskRunner remoteTaskRunner;
  private WorkerCuratorCoordinator workerCuratorCoordinator;
  private WorkerTaskMonitor workerTaskMonitor;

  private TestTask task;

  private Worker worker;

  @Before
  public void setUp() throws Exception
  {
    testingCluster = new TestingCluster(1);
    testingCluster.start();

    cf = CuratorFrameworkFactory.builder()
                                .connectString(testingCluster.getConnectString())
                                .retryPolicy(new ExponentialBackoffRetry(1, 10))
                                .compressionProvider(new PotentiallyGzippedCompressionProvider(false))
                                .build();
    cf.start();
    cf.create().creatingParentsIfNeeded().forPath(basePath);

    task = makeTask(TaskStatus.success("task"));
  }

  @After
  public void tearDown() throws Exception
  {
    remoteTaskRunner.stop();
    workerCuratorCoordinator.stop();
    workerTaskMonitor.stop();
    cf.close();
    testingCluster.stop();
  }

  @Test
  public void testRunNoExistingTask() throws Exception
  {
    doSetup();

    remoteTaskRunner.run(task);
  }

  @Test
  public void testRunTooMuchZKData() throws Exception
  {
    ServiceEmitter emitter = EasyMock.createMock(ServiceEmitter.class);
    EmittingLogger.registerEmitter(emitter);
    EasyMock.replay(emitter);

    doSetup();

    remoteTaskRunner.run(makeTask(TaskStatus.success(new String(new char[5000]))));

    EasyMock.verify(emitter);
  }

  @Test
  public void testRunSameAvailabilityGroup() throws Exception
  {
    doSetup();

    TestRealtimeTask theTask = new TestRealtimeTask(
        "rt1",
        new TaskResource("rt1", 1),
        "foo",
        TaskStatus.running("rt1")
    );
    remoteTaskRunner.run(theTask);
    remoteTaskRunner.run(
        new TestRealtimeTask("rt2", new TaskResource("rt1", 1), "foo", TaskStatus.running("rt2"))
    );
    remoteTaskRunner.run(
        new TestRealtimeTask("rt3", new TaskResource("rt2", 1), "foo", TaskStatus.running("rt3"))
    );

    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    while (remoteTaskRunner.getRunningTasks().size() < 2) {
      Thread.sleep(100);
      if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
        throw new ISE("Cannot find running task");
      }
    }

    Assert.assertTrue(remoteTaskRunner.getRunningTasks().size() == 2);
    Assert.assertTrue(remoteTaskRunner.getPendingTasks().size() == 1);
    Assert.assertTrue(remoteTaskRunner.getPendingTasks().iterator().next().getTask().getId().equals("rt2"));
  }

  @Test
  public void testRunWithCapacity() throws Exception
  {
    doSetup();

    TestRealtimeTask theTask = new TestRealtimeTask(
        "rt1",
        new TaskResource("rt1", 1),
        "foo",
        TaskStatus.running("rt1")
    );
    remoteTaskRunner.run(theTask);
    remoteTaskRunner.run(
        new TestRealtimeTask("rt2", new TaskResource("rt2", 3), "foo", TaskStatus.running("rt2"))
    );
    remoteTaskRunner.run(
        new TestRealtimeTask("rt3", new TaskResource("rt3", 2), "foo", TaskStatus.running("rt3"))
    );

    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    while (remoteTaskRunner.getRunningTasks().size() < 2) {
      Thread.sleep(100);
      if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
        throw new ISE("Cannot find running task");
      }
    }

    Assert.assertTrue(remoteTaskRunner.getRunningTasks().size() == 2);
    Assert.assertTrue(remoteTaskRunner.getPendingTasks().size() == 1);
    Assert.assertTrue(remoteTaskRunner.getPendingTasks().iterator().next().getTask().getId().equals("rt2"));
  }

  @Test
  public void testFailure() throws Exception
  {
    doSetup();

    ListenableFuture<TaskStatus> future = remoteTaskRunner.run(makeTask(TaskStatus.running("task")));
    final String taskStatus = joiner.join(statusPath, "task");

    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    while (cf.checkExists().forPath(taskStatus) == null) {
      Thread.sleep(100);
      if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
        throw new ISE("Cannot find running task");
      }
    }
    Assert.assertTrue(remoteTaskRunner.getRunningTasks().iterator().next().getTask().getId().equals("task"));

    cf.delete().forPath(taskStatus);

    TaskStatus status = future.get();

    Assert.assertEquals(status.getStatusCode(), TaskStatus.Status.FAILED);
  }

  @Test
  public void testBootstrap() throws Exception
  {
    cf.create()
      .creatingParentsIfNeeded()
      .withMode(CreateMode.EPHEMERAL)
      .forPath(joiner.join(statusPath, "first"), jsonMapper.writeValueAsBytes(TaskStatus.running("first")));
    cf.create()
      .creatingParentsIfNeeded()
      .withMode(CreateMode.EPHEMERAL)
      .forPath(joiner.join(statusPath, "second"), jsonMapper.writeValueAsBytes(TaskStatus.running("second")));

    doSetup();

    Set<String> existingTasks = Sets.newHashSet();
    for (ZkWorker zkWorker : remoteTaskRunner.getWorkers()) {
      existingTasks.addAll(zkWorker.getRunningTasks().keySet());
    }

    Assert.assertTrue(existingTasks.size() == 2);
    Assert.assertTrue(existingTasks.contains("first"));
    Assert.assertTrue(existingTasks.contains("second"));

    remoteTaskRunner.bootstrap(Arrays.<Task>asList(makeTask(TaskStatus.running("second"))));

    Set<String> runningTasks = Sets.newHashSet(
        Iterables.transform(
            remoteTaskRunner.getRunningTasks(),
            new Function<RemoteTaskRunnerWorkItem, String>()
            {
              @Override
              public String apply(RemoteTaskRunnerWorkItem input)
              {
                return input.getTask().getId();
              }
            }
        )
    );

    Assert.assertTrue(runningTasks.size() == 1);
    Assert.assertTrue(runningTasks.contains("second"));
    Assert.assertFalse(runningTasks.contains("first"));
  }

  @Test
  public void testRunWithTaskComplete() throws Exception
  {
    cf.create()
      .creatingParentsIfNeeded()
      .withMode(CreateMode.EPHEMERAL)
      .forPath(joiner.join(statusPath, task.getId()), jsonMapper.writeValueAsBytes(TaskStatus.success(task.getId())));

    doSetup();

    remoteTaskRunner.bootstrap(Arrays.<Task>asList(task));

    ListenableFuture<TaskStatus> future = remoteTaskRunner.run(task);

    TaskStatus status = future.get();

    Assert.assertEquals(TaskStatus.Status.SUCCESS, status.getStatusCode());
  }

  @Test
  public void testWorkerRemoved() throws Exception
  {
    doSetup();
    remoteTaskRunner.bootstrap(Lists.<Task>newArrayList());
    Future<TaskStatus> future = remoteTaskRunner.run(makeTask(TaskStatus.running("task")));

    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    while (cf.checkExists().forPath(joiner.join(statusPath, "task")) == null) {
      Thread.sleep(100);
      if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
        throw new ISE("Cannot find running task");
      }
    }

    workerCuratorCoordinator.stop();

    TaskStatus status = future.get();

    Assert.assertEquals(TaskStatus.Status.FAILED, status.getStatusCode());
  }

  private void doSetup() throws Exception
  {
    makeWorker();
    makeRemoteTaskRunner();
    makeTaskMonitor();
  }

  private TestTask makeTask(TaskStatus status)
  {
    return new TestTask(
        status.getId(),
        "dummyDs",
        Lists.<DataSegment>newArrayList(
            new DataSegment(
                "dummyDs",
                new Interval(new DateTime(), new DateTime()),
                new DateTime().toString(),
                null,
                null,
                null,
                null,
                0,
                0
            )
        ),
        Lists.<AggregatorFactory>newArrayList(),
        status
    );
  }

  private void makeTaskMonitor() throws Exception
  {
    workerCuratorCoordinator = new WorkerCuratorCoordinator(
        jsonMapper,
        new ZkPathsConfig()
        {
          @Override
          public String getZkBasePath()
          {
            return basePath;
          }
        },
        new TestRemoteTaskRunnerConfig(),
        cf,
        worker
    );
    workerCuratorCoordinator.start();

    final File tmp = Files.createTempDir();

    // Start a task monitor
    workerTaskMonitor = new WorkerTaskMonitor(
        jsonMapper,
        cf,
        workerCuratorCoordinator,
        new ThreadPoolTaskRunner(
            new TaskToolboxFactory(
                new TaskConfig(tmp.toString(), null, null, 0),
                null, null, null, null, null, null, null, null, null, jsonMapper
            )
        ),
        new WorkerConfig().setCapacity(1)
    );
    jsonMapper.registerSubtypes(new NamedType(TestTask.class, "test"));
    jsonMapper.registerSubtypes(new NamedType(TestRealtimeTask.class, "test_realtime"));
    workerTaskMonitor.start();
  }

  private void makeRemoteTaskRunner() throws Exception
  {
    remoteTaskRunner = new RemoteTaskRunner(
        jsonMapper,
        new TestRemoteTaskRunnerConfig(),
        new ZkPathsConfig()
        {
          @Override
          public String getZkBasePath()
          {
            return basePath;
          }
        },
        cf,
        new SimplePathChildrenCacheFactory.Builder().build(),
        DSuppliers.of(new AtomicReference<WorkerSetupData>(new WorkerSetupData("0", 0, 1, null, null))),
        null
    );

    remoteTaskRunner.start();
  }

  private void makeWorker() throws Exception
  {
    worker = new Worker(
        "worker",
        "localhost",
        3,
        "0"
    );

    cf.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(
        announcementsPath,
        jsonMapper.writeValueAsBytes(worker)
    );
  }

  private static class TestRemoteTaskRunnerConfig extends RemoteTaskRunnerConfig
  {
    @Override
    public boolean isCompressZnodes()
    {
      return false;
    }

    @Override
    public Period getTaskAssignmentTimeout()
    {
      return new Period(60000);
    }

    @Override
    public long getMaxZnodeBytes()
    {
      return 1000;
    }

    @Override
    public String getWorkerVersion()
    {
      return "";
    }
  }
}