/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mantisrx.server.master.persistence;

import io.mantisrx.master.events.LifecycleEventPublisher;
import io.mantisrx.master.jobcluster.IJobClusterMetadata;
import io.mantisrx.master.jobcluster.job.IMantisJobMetadata;
import io.mantisrx.master.jobcluster.job.IMantisStageMetadata;
import io.mantisrx.master.jobcluster.job.worker.IMantisWorkerMetadata;
import io.mantisrx.master.jobcluster.job.worker.JobWorker;
import io.mantisrx.master.resourcecluster.DisableTaskExecutorsRequest;
import io.mantisrx.server.master.domain.DataFormatAdapter;
import io.mantisrx.server.master.domain.JobClusterDefinitionImpl.CompletedJob;
import io.mantisrx.server.master.resourcecluster.ClusterID;
import io.mantisrx.server.master.resourcecluster.TaskExecutorID;
import io.mantisrx.server.master.resourcecluster.TaskExecutorRegistration;
import io.mantisrx.server.master.store.InvalidJobException;
import io.mantisrx.server.master.store.MantisJobMetadataWritable;
import io.mantisrx.server.master.store.MantisStageMetadata;
import io.mantisrx.server.master.store.MantisStageMetadataWritable;
import io.mantisrx.server.master.store.MantisStorageProvider;
import io.mantisrx.server.master.store.MantisWorkerMetadataWritable;
import io.mantisrx.server.master.store.NamedJob;
import io.mantisrx.shaded.com.fasterxml.jackson.core.JsonProcessingException;
import io.mantisrx.shaded.com.fasterxml.jackson.core.type.TypeReference;
import io.mantisrx.shaded.com.fasterxml.jackson.databind.DeserializationFeature;
import io.mantisrx.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import io.mantisrx.shaded.com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.mantisrx.shaded.com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mantisrx.shaded.com.google.common.collect.ImmutableList;
import io.mantisrx.shaded.com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;


public class MantisStorageProviderAdapter implements IMantisStorageProvider {

    private static final Logger logger = LoggerFactory.getLogger(MantisStorageProviderAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String JOB_STAGEDATA_NS = "MantisJobStageData";
    private static final String ARCHIVED_JOB_STAGEDATA_NS = "MantisArchivedJobStageData";
    private static final String WORKERS_NS = "MantisWorkers";
    private static final String ARCHIVED_WORKERS_NS = "MantisArchivedWorkers";
    private static final String NAMED_JOBS_NS = "MantisNamedJobs";
    private static final String NAMED_COMPLETEDJOBS_NS = "MantisNamedJobCompletedJobs";
    private static final String ACTIVE_ASGS_NS = "MantisActiveASGs";
    private static final String TASK_EXECUTOR_REGISTRATION = "TaskExecutorRegistration";
    private static final String DISABLE_TASK_EXECUTOR_REQUESTS = "MantisDisableTaskExecutorRequests";
    private static final String CONTROLPLANE_NS = "mantis_controlplane";
    private static final String NAMED_JOB_SECONDARY_KEY = "jobNameInfo";
    private static final int WORKER_BATCH_SIZE = 1000;
    private static final int WORKER_MAX_INDEX = 30000;

    static {
        mapper
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule());
    }

    private final MantisStorageProvider sProvider;
    private final LifecycleEventPublisher eventPublisher;

    public MantisStorageProviderAdapter(MantisStorageProvider actualStorageProvider, LifecycleEventPublisher eventPublisher) {
        this.sProvider = actualStorageProvider;
        this.eventPublisher = eventPublisher;
    }

    protected String getNamespace(final String namespace) {
        return namespace;
    }

    protected String getJobMetadataFieldName() {
        return "jobMetadata";
    }

    protected String getStageMetadataFieldPrefix() {
        return "stageMetadata";
    }

    protected String getJobStageFieldName(int stageNum) {
        return String.format("%s-%d", getStageMetadataFieldPrefix(), stageNum);
    }

    private boolean jobIsValid(MantisJobMetadataWritable job) {
        final int numStages = job.getNumStages();
        final Collection<? extends MantisStageMetadata> stageMetadata = job.getStageMetadata();
        if (stageMetadata == null) {
            logger.error("Could not find stage metadata for jobId {}", job.getJobId());
            return false;
        }
        if (stageMetadata.size() != numStages) {
            logger.error("Invalid stage metadata for job {}: stage count mismatch expected {} vs found {}",
                job.getJobId(), numStages, stageMetadata.size());
            return false;
        }
        return true;
    }

    private MantisJobMetadataWritable readJobStageData(final String namespace, final String jobId)
        throws IOException {
        return readJobStageData(jobId, sProvider.getAll(getNamespace(namespace), jobId));
    }

    private MantisJobMetadataWritable readJobStageData(final String jobId, final Map<String, String> items) throws IOException {
        String jobMetadataColumnName = getJobMetadataFieldName();

        final AtomicReference<MantisJobMetadataWritable> wrapper = new AtomicReference<>();
        final List<MantisStageMetadataWritable> stages = new LinkedList<>();
        items.forEach(
            (k, v) -> {
                try {
                    if (k != null && v != null) {
                        if (jobMetadataColumnName.equals(k)) {
                            wrapper.set(mapper.readValue(v, MantisJobMetadataWritable.class));
                        } else if (k.startsWith(getStageMetadataFieldPrefix())) {
                            stages.add(mapper.readValue(v, MantisStageMetadataWritable.class));
                        }
                    }
                } catch (JsonProcessingException e) {
                    logger.warn(
                        "failed to deserialize job metadata for jobId {}, column name {}", jobId, k, e);
                }
            });
        final MantisJobMetadataWritable job = wrapper.get();
        if (job == null) {
            throw new IOException("No " + jobMetadataColumnName + " column found for key jobId=" + jobId);
        }

        if (stages.isEmpty()) {
            throw new IOException(
                "No stage metadata columns with prefix "
                    + getStageMetadataFieldPrefix()
                    + " found for jobId="
                    + jobId);
        }
        for (MantisStageMetadataWritable msmd : stages) {
            job.addJobStageIfAbsent(msmd);
        }
        if (jobIsValid(job)) {
            return job;
        }
        throw new IOException(String.format("Invalid job for jobId %s", jobId));
    }

    @Override
    public void storeNewJob(IMantisJobMetadata jobMetadata) throws Exception {
        MantisJobMetadataWritable mjmw = DataFormatAdapter.convertMantisJobMetadataToMantisJobMetadataWriteable(jobMetadata);
        try {
            sProvider.upsert(getNamespace(JOB_STAGEDATA_NS), jobMetadata.getJobId().toString(), getJobMetadataFieldName(), mapper.writeValueAsString(mjmw));
        } catch (IOException e) {
            throw new Exception(e);
        }
    }

    @Override
    public void updateJob(IMantisJobMetadata jobMetadata) throws Exception {
        MantisJobMetadataWritable mjmw = DataFormatAdapter.convertMantisJobMetadataToMantisJobMetadataWriteable(jobMetadata);
        sProvider.upsert(getNamespace(JOB_STAGEDATA_NS), jobMetadata.getJobId().toString(), getJobMetadataFieldName(), mapper.writeValueAsString(mjmw));
    }

    @Override
    public void archiveJob(String jobId) throws IOException {
        Map<String, String> all = sProvider.getAll(getNamespace(JOB_STAGEDATA_NS), jobId);
        int workerMaxPartitionKey = workerMaxPartitionKey(readJobStageData(jobId, all));
        sProvider.upsertAll(getNamespace(ARCHIVED_JOB_STAGEDATA_NS), jobId, all);
        sProvider.deleteAll(getNamespace(JOB_STAGEDATA_NS), jobId);

        for (int i = 0; i < workerMaxPartitionKey; i += WORKER_BATCH_SIZE) {
            String pkey = makeBucketizedPartitionKey(jobId, i);
            Map<String, String> workersData = sProvider.getAll(getNamespace(WORKERS_NS), pkey);
            sProvider.upsertAll(getNamespace(ARCHIVED_WORKERS_NS), pkey, workersData);
            sProvider.deleteAll(getNamespace(WORKERS_NS), pkey);
        }
    }

    @Override
    public void deleteJob(String jobId) throws Exception {
        MantisJobMetadataWritable jobMeta = readJobStageData(getNamespace(JOB_STAGEDATA_NS), jobId);
        int workerMaxPartitionKey = workerMaxPartitionKey(jobMeta);

        sProvider.deleteAll(getNamespace(JOB_STAGEDATA_NS), jobId);
        rangeOperation(workerMaxPartitionKey, idx -> {
            try {
                sProvider.deleteAll(getNamespace(WORKERS_NS), makeBucketizedPartitionKey(jobId, idx));
            } catch (IOException e) {
                logger.warn("failed to delete worker for jobId {} with index {}", jobId, idx, e);
            }
        });

        // delete from archive as well
        sProvider.deleteAll(getNamespace(ARCHIVED_JOB_STAGEDATA_NS), jobId);
        rangeOperation(workerMaxPartitionKey, idx -> {
            try {
                sProvider.deleteAll(getNamespace(ARCHIVED_WORKERS_NS), makeBucketizedPartitionKey(jobId, idx));
            } catch (IOException e) {
                logger.warn("failed to delete worker for jobId {} with index {}", jobId, idx, e);
            }
        });
    }

    @Override
    public void storeMantisStage(IMantisStageMetadata msmd) throws IOException {
        MantisStageMetadataWritable msmw = DataFormatAdapter.convertMantisStageMetadataToMantisStageMetadataWriteable(msmd);
        sProvider.upsert(getNamespace(JOB_STAGEDATA_NS), msmd.getJobId().toString(), getJobStageFieldName(msmd.getStageNum()), mapper.writeValueAsString(msmw));
    }

    @Override
    public void updateMantisStage(IMantisStageMetadata msmd) throws IOException {
        storeMantisStage(msmd);
    }

    private int workerMaxPartitionKey(MantisJobMetadataWritable jobMetadata) {
        try {
            return jobMetadata.getNextWorkerNumberToUse();
        } catch (Exception ignored) {
        }
        // big number in case we don't find the job
        return WORKER_MAX_INDEX;
    }

    private int bucketizePartitionKey(int num) {
        return (int) (WORKER_BATCH_SIZE * Math.ceil(1.0 * num / WORKER_BATCH_SIZE));
    }

    private String makeBucketizedPartitionKey(String pkeyPart, int suffix) {
        int bucketized = bucketizePartitionKey(suffix);
        return String.format("%s-%d", pkeyPart, bucketized);
    }

    private String makeBucketizedSecondaryKey(int stageNum, int workerIdx, int workerNum) {
        return String.format("%d-%d-%d", stageNum, workerIdx, workerNum);
    }

    private void rangeOperation(int nextJobNumber, Consumer<Integer> fn) {
        int maxIndex = bucketizePartitionKey(nextJobNumber);
        for (int i = 0; i <= maxIndex; i += WORKER_BATCH_SIZE) {
            fn.accept(i);
        }
    }

    @Override
    public void storeWorker(IMantisWorkerMetadata workerMetadata) throws IOException {
        storeWorkers(workerMetadata.getJobId(), Collections.singletonList(workerMetadata));
    }

    @Override
    public void storeWorkers(List<IMantisWorkerMetadata> workers) throws IOException {
        for (IMantisWorkerMetadata worker : workers) {
            final MantisWorkerMetadataWritable mwmw = DataFormatAdapter.convertMantisWorkerMetadataToMantisWorkerMetadataWritable(worker);
            final String pkey = makeBucketizedPartitionKey(mwmw.getJobId(), mwmw.getWorkerNumber());
            final String skey = makeBucketizedSecondaryKey(mwmw.getStageNum(), mwmw.getWorkerIndex(), mwmw.getWorkerNumber());
            sProvider.upsert(getNamespace(WORKERS_NS), pkey, skey, mapper.writeValueAsString(mwmw));
        }
    }

    @Override
    public void storeAndUpdateWorkers(IMantisWorkerMetadata existingWorker, IMantisWorkerMetadata newWorker) throws IOException {
        storeWorkers(ImmutableList.of(existingWorker, newWorker));
    }

    @Override
    public void updateWorker(IMantisWorkerMetadata worker) throws IOException {
        storeWorker(worker);
    }

    private Map<String, List<MantisWorkerMetadataWritable>> getAllWorkersByJobId(final String namespace) throws IOException {
        Map<String, List<MantisWorkerMetadataWritable>> workersByJobId = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> worker : sProvider.getAllRows(namespace).entrySet()) {
            if (worker.getValue().values().size() <= 0) {
                continue;
            }
            List<MantisWorkerMetadataWritable> workers = worker.getValue().values().stream()
                .map(data -> {
                    try {
                        return mapper.readValue(data, MantisWorkerMetadataWritable.class);
                    } catch (JsonProcessingException e) {
                        logger.warn("failed to parse worker against pkey {} json {}", worker.getKey(), data, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            workersByJobId
                .computeIfAbsent(workers.get(0).getJobId(), k -> Lists.newArrayList())
                .addAll(workers);
        }
        return workersByJobId;
    }

    @Override
    public List<IMantisJobMetadata> loadAllJobs() throws IOException {
        logger.info("MantisStorageProviderAdapter:Enter loadAllJobs");
        final Map<String, List<MantisWorkerMetadataWritable>> workersByJobId = getAllWorkersByJobId(getNamespace(WORKERS_NS));
        final List<IMantisJobMetadata> jobMetas = Lists.newArrayList();
        final Map<String, Map<String, String>> allRows = sProvider.getAllRows(getNamespace(JOB_STAGEDATA_NS));
        for (Map.Entry<String, Map<String, String>> jobInfo : allRows.entrySet()) {
            final String jobId = jobInfo.getKey();
            try {
                final MantisJobMetadataWritable jobMeta = readJobStageData(jobId, jobInfo.getValue());
                if (CollectionUtils.isEmpty(workersByJobId.get(jobId))) {
                    logger.warn("No workers found for job {}, skipping", jobId);
                    continue;
                }
                for (MantisWorkerMetadataWritable workerMeta : workersByJobId.get(jobId)) {
                    jobMeta.addWorkerMedata(workerMeta.getStageNum(), workerMeta, null);
                }
                jobMetas.add(DataFormatAdapter.convertMantisJobWriteableToMantisJobMetadata(jobMeta, eventPublisher));
            } catch (Exception e) {
                logger.warn("Exception loading job {}", jobId, e);
            }
        }
        // need to load all workers for the jobMeta and then ensure they are added to jobMetas!
        logger.info("MantisStorageProviderAdapter:Exit loadAllJobs {}", jobMetas.size());
        return jobMetas;
    }

    @Override
    public Observable<IMantisJobMetadata> loadAllArchivedJobs() {
        return Observable.create(
            subscriber -> {
                try {
                    for (String pkey : sProvider.getAllPartitionKeys(getNamespace(ARCHIVED_JOB_STAGEDATA_NS))) {
                        Optional<IMantisJobMetadata> jobMetaOpt = loadArchivedJob(pkey);
                        jobMetaOpt.ifPresent(subscriber::onNext);
                    }
                    subscriber.onCompleted();
                } catch (IOException e) {
                    subscriber.onError(e);
                }
            });
    }

    @Override
    public List<IJobClusterMetadata> loadAllJobClusters() throws IOException {
        AtomicInteger failedCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        final List<IJobClusterMetadata> jobClusters = Lists.newArrayList();
        for (String name : sProvider.getAllPartitionKeys(getNamespace(NAMED_JOBS_NS))) {
            try {
                final NamedJob jobCluster = getJobCluster(getNamespace(NAMED_JOBS_NS), name);
                jobClusters.add(DataFormatAdapter.convertNamedJobToJobClusterMetadata(jobCluster));
                successCount.getAndIncrement();
            } catch (Exception e) {
                logger.error("Exception {} getting job cluster for {} ", e.getMessage(), name, e);
                failedCount.getAndIncrement();
            }
        }
        return jobClusters;
    }


    @Override
    public List<CompletedJob> loadAllCompletedJobs() throws IOException {
        AtomicInteger failedCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();

        final List<CompletedJob> completedJobsList = Lists.newArrayList();
        final String namespace = getNamespace(NAMED_COMPLETEDJOBS_NS);
        for (String pkey : sProvider.getAllPartitionKeys(namespace)) {
            sProvider.getAll(namespace, pkey).values()
                .forEach(data -> {
                    try {
                        NamedJob.CompletedJob cj = mapper.readValue(data, NamedJob.CompletedJob.class);
                        CompletedJob completedJob = DataFormatAdapter.convertNamedJobCompletedJobToCompletedJob(cj);
                        completedJobsList.add(completedJob);
                        successCount.getAndIncrement();
                    } catch (JsonProcessingException e) {
                        logger.warn("failed to parse CompletedJob from {} {}", pkey, data, e);
                        failedCount.getAndIncrement();
                    }
                });
        }

        logger.info("Read and converted job clusters. Successful - {}, Failed - {}", successCount.get(), failedCount.get());
        return completedJobsList;
    }


    @Override
    public void archiveWorker(IMantisWorkerMetadata mwmd) throws IOException {
        MantisWorkerMetadataWritable worker = DataFormatAdapter.convertMantisWorkerMetadataToMantisWorkerMetadataWritable(mwmd);
        String pkey = makeBucketizedPartitionKey(worker.getJobId(), worker.getWorkerNumber());
        String skey = makeBucketizedSecondaryKey(worker.getStageNum(), worker.getWorkerIndex(), worker.getStageNum());
        sProvider.delete(getNamespace(WORKERS_NS), pkey, skey);
        sProvider.upsert(getNamespace(ARCHIVED_WORKERS_NS), pkey, skey, mapper.writeValueAsString(worker));
    }

    @Override
    public List<IMantisWorkerMetadata> getArchivedWorkers(String jobId) throws IOException {
        // try loading the active job first and then the archived job
        MantisJobMetadataWritable jobInfo;
        try {
            jobInfo = readJobStageData(getNamespace(JOB_STAGEDATA_NS), jobId);
        } catch (Exception e) {
            jobInfo = readJobStageData(getNamespace(ARCHIVED_JOB_STAGEDATA_NS), jobId);
        }
        if (jobInfo == null) {
            return Collections.emptyList();
        }
        int workerMaxPartitionKey = workerMaxPartitionKey(jobInfo);
        final List<IMantisWorkerMetadata> archivedWorkers = Lists.newArrayList();
        rangeOperation(workerMaxPartitionKey, idx -> {
            String pkey = makeBucketizedPartitionKey(jobId, idx);
            final Map<String, String> items;
            try {
                items = sProvider.getAll(getNamespace(ARCHIVED_WORKERS_NS), pkey);
                for (Map.Entry<String, String> entry : items.entrySet()) {
                    try {
                        final JobWorker jobWorker = DataFormatAdapter.convertMantisWorkerMetadataWriteableToMantisWorkerMetadata(
                            mapper.readValue(entry.getValue(), MantisWorkerMetadataWritable.class),
                            eventPublisher);
                        archivedWorkers.add(jobWorker.getMetadata());
                    } catch (Exception e) {
                        logger.warn("Exception converting worker for jobId {} ({}, {})", jobId, pkey, entry.getKey(), e);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error reading archive workers for jobId {} for pkey {}", jobId, pkey, e);
            }
        });
        return archivedWorkers;
    }

    @Override
    public void createJobCluster(IJobClusterMetadata jobCluster) throws Exception {
        updateJobCluster(jobCluster);
    }

    @Override
    public void updateJobCluster(IJobClusterMetadata jobCluster) throws Exception {
        sProvider.upsert(
            getNamespace(NAMED_JOBS_NS),
            jobCluster.getJobClusterDefinition().getName(),
            NAMED_JOB_SECONDARY_KEY,
            mapper.writeValueAsString(DataFormatAdapter.convertJobClusterMetadataToNamedJob(jobCluster)));
    }

    @Override
    public void deleteJobCluster(String name) throws Exception {
        NamedJob namedJob = getJobCluster(getNamespace(NAMED_JOBS_NS), name);
        sProvider.deleteAll(getNamespace(NAMED_JOBS_NS), name);
        rangeOperation((int) namedJob.getNextJobNumber(),
            idx -> {
                try {
                    sProvider.deleteAll(getNamespace(NAMED_COMPLETEDJOBS_NS), makeBucketizedPartitionKey(name, idx));
                } catch (IOException e) {
                    logger.warn("failed to completed job for named job {} with index {}", name, idx, e);
                }
            });
    }

    private NamedJob getJobCluster(String namespace, String name) throws Exception {
        String data = sProvider.get(namespace, name, NAMED_JOB_SECONDARY_KEY);
        return mapper.readValue(data, NamedJob.class);
    }

    private int parseJobId(String jobId) {
        return Integer.parseInt(jobId.split("-")[1]);
    }

    @Override
    public void storeCompletedJobForCluster(String name, CompletedJob job) throws IOException {
        int jobIdx = parseJobId(job.getJobId());
        NamedJob.CompletedJob completedJob = DataFormatAdapter.convertCompletedJobToNamedJobCompletedJob(job);
        sProvider.upsert(getNamespace(NAMED_COMPLETEDJOBS_NS),
            makeBucketizedPartitionKey(name, jobIdx),
            String.valueOf(jobIdx),
            mapper.writeValueAsString(completedJob));
    }

    @Override
    public void removeCompletedJobForCluster(String name, String jobId) throws IOException {
        int jobIdx = parseJobId(jobId);
        sProvider.deleteAll(getNamespace(NAMED_COMPLETEDJOBS_NS),
            makeBucketizedPartitionKey(name, jobIdx));
    }

    @Override
    public Optional<IMantisJobMetadata> loadArchivedJob(String jobId) throws IOException {
        try {
            MantisJobMetadataWritable jmw = readJobStageData(getNamespace(ARCHIVED_JOB_STAGEDATA_NS), jobId);

            final List<IMantisWorkerMetadata> archivedWorkers = getArchivedWorkers(jmw.getJobId());
            if (CollectionUtils.isNotEmpty(archivedWorkers)) {
                for (IMantisWorkerMetadata w : archivedWorkers) {
                    try {
                        MantisWorkerMetadataWritable wmw = DataFormatAdapter.convertMantisWorkerMetadataToMantisWorkerMetadataWritable(w);
                        jmw.addWorkerMedata(w.getStageNum(), wmw, null);
                    } catch (InvalidJobException e) {
                        logger.warn(
                            "Unexpected error adding worker index={}, number={} to job {}",
                            w.getWorkerIndex(), w.getWorkerNumber(), jmw.getJobId());
                    }
                }
                return Optional.of(DataFormatAdapter.convertMantisJobWriteableToMantisJobMetadata(jmw, eventPublisher));
            }
        } catch (Exception e) {
            logger.error("Exception loading archived job {}", jobId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<String> initActiveVmAttributeValuesList() throws IOException {
        final String data = sProvider.get(getNamespace(ACTIVE_ASGS_NS),
            "activeASGs", "thelist");
        logger.info("read active VMs data {} from Cass", data);
        if (StringUtils.isBlank(data)) {
            return Collections.emptyList();
        }
        return mapper.readValue(data, new TypeReference<List<String>>() {});
    }

    @Override
    public void setActiveVmAttributeValuesList(List<String> vmAttributesList) throws IOException {
        logger.info("Setting active ASGs {}", vmAttributesList);
        sProvider.upsert(getNamespace(ACTIVE_ASGS_NS),
            "activeASGs", "thelist",
            mapper.writeValueAsString(vmAttributesList));
    }

    @Override
    public TaskExecutorRegistration getTaskExecutorFor(TaskExecutorID taskExecutorID) throws IOException {
        try {
            final String value =
                sProvider.get(getNamespace(CONTROLPLANE_NS),
                        TASK_EXECUTOR_REGISTRATION + "-" + taskExecutorID.getResourceId(),
                    taskExecutorID.getResourceId());
            return mapper.readValue(value, TaskExecutorRegistration.class);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void storeNewTaskExecutor(TaskExecutorRegistration registration) throws IOException {
        final String resourceId = registration.getTaskExecutorID().getResourceId();
        final String keyId = String.format("%s-%s", TASK_EXECUTOR_REGISTRATION, resourceId);
        sProvider.upsert(getNamespace(CONTROLPLANE_NS), keyId, resourceId,
            mapper.writeValueAsString(registration));
    }

    @Override
    public void storeNewDisableTaskExecutorRequest(DisableTaskExecutorsRequest request) throws IOException {
        String data = mapper.writeValueAsString(request);
        sProvider.upsert(
            getNamespace(DISABLE_TASK_EXECUTOR_REQUESTS),
            request.getClusterID().getResourceID(),
            request.getHash(), data);
    }

    @Override
    public void deleteExpiredDisableTaskExecutorRequest(DisableTaskExecutorsRequest request) throws IOException {
        sProvider.delete(
            getNamespace(DISABLE_TASK_EXECUTOR_REQUESTS),
            request.getClusterID().getResourceID(),
            request.getHash());
    }

    @Override
    public List<DisableTaskExecutorsRequest> loadAllDisableTaskExecutorsRequests(ClusterID clusterID) throws IOException {

        return sProvider.getAll(getNamespace(DISABLE_TASK_EXECUTOR_REQUESTS), clusterID.getResourceID())
            .values().stream()
            .map(
                value -> {
                    try {
                        return mapper.readValue(value, DisableTaskExecutorsRequest.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
            .collect(Collectors.toList());
    }
}
