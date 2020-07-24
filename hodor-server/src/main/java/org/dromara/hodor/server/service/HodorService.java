package org.dromara.hodor.server.service;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.dromara.hodor.common.exception.HodorException;
import org.dromara.hodor.common.utils.CopySets;
import org.dromara.hodor.common.utils.ThreadUtils;
import org.dromara.hodor.core.entity.CopySet;
import org.dromara.hodor.core.entity.HodorMetadata;
import org.dromara.hodor.core.service.JobInfoService;
import org.dromara.hodor.server.component.LifecycleComponent;
import org.dromara.hodor.server.listener.MetadataChangeListener;
import org.dromara.hodor.server.listener.ServerNodeChangeListener;
import org.springframework.stereotype.Service;

/**
 * hodor service
 *
 * @author tomgs
 * @since 2020/6/29
 */
@Slf4j
@Service
public class HodorService implements LifecycleComponent {

    private final LeaderService leaderService;
    private final RegisterService registerService;
    private final JobInfoService jobInfoService;
    private final Integer leastNodeCount;
    private final Integer replicaCount;
    private final Integer scatterWidth;
    private final Set<String> leaderCopySet;

    public HodorService(final LeaderService leaderService, final RegisterService registerService, final JobInfoService jobInfoService) {
        this.leaderService = leaderService;
        this.registerService = registerService;
        this.jobInfoService = jobInfoService;
        this.replicaCount = 3;
        this.leastNodeCount = 3;
        this.scatterWidth = 2;
        this.leaderCopySet = Sets.newConcurrentHashSet();
    }

    @Override
    public void start() {
        //init data
        registerService.registryMetadataListener(new MetadataChangeListener());
        //select leader
        Integer currRunningNodeCount = registerService.getRunningNodeCount();
        while (currRunningNodeCount < leastNodeCount) {
            ThreadUtils.sleep(TimeUnit.MILLISECONDS, 1000);
            currRunningNodeCount = registerService.getRunningNodeCount();
        }
        leaderService.electLeader(() -> {
            log.info("to be leader.");
            registerService.registryServerNodeListener(new ServerNodeChangeListener());
            // after to be leader write here
            List<String> currRunningNodes = registerService.getRunningNodes();
            if (CollectionUtils.isEmpty(currRunningNodes)) {
                throw new HodorException("running node count is 0.");
            }

            List<List<String>> copySetIps = CopySets.buildCopySets(currRunningNodes, replicaCount, scatterWidth);
            int setsNum = Math.max(copySetIps.size(), currRunningNodes.size());
            // distribution copySet
            List<CopySet> copySets = Lists.newArrayList();
            for (int i = 0; i < setsNum; i++) {
                int setsIndex = setsNum % copySetIps.size();
                List<String> copySetIp = copySetIps.get(setsIndex);
                CopySet copySet = new CopySet();
                copySet.setId(setsIndex);
                copySet.setServers(copySetIp);
                copySets.add(copySet);
            }

            // get metadata and update
            int jobCount = jobInfoService.queryAssignableJobCount();
            int offset = (int) Math.ceil((double) jobCount / setsNum);
            List<Integer> interval = Lists.newArrayList();
            for (int i = 0; i < setsNum; i++) {
                Integer index = jobInfoService.queryJobHashIdByOffset(offset * i);
                interval.add(index);
            }
            for (int i = 0; i < interval.size(); i++) {
                CopySet copySet = copySets.get(i);
                if (i == interval.size() - 1) {
                    copySet.setDataInterval(Lists.newArrayList(interval.get(i)));
                } else {
                    copySet.setDataInterval(Lists.newArrayList(interval.get(i), interval.get(i + 1)));
                }
                copySet.setLeader(selectLeaderCopySet(copySet));
            }

            final HodorMetadata metadata = HodorMetadata.builder()
                .nodes(currRunningNodes)
                .interval(interval)
                .copySets(copySets)
                .build();
            registerService.createMetadata(metadata);
        });
        //job assign
    }

    private String selectLeaderCopySet(CopySet copySet) {
        List<String> servers = copySet.getServers();
        // copy set leader election.
        servers.sort(Comparable::compareTo);
        for (String leader : servers) {
            if (!isCopySetLeader(leader)) {
                leaderCopySet.add(leader);
                return leader;
            }
        }
        // default copy sets leader
        return servers.get(0);
    }

    private boolean isCopySetLeader(String leader) {
        return leaderCopySet.contains(leader);
    }

    @Override
    public void stop() {
        leaderCopySet.clear();
    }

}
