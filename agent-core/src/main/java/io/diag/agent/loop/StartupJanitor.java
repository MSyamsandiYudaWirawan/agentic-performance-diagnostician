package io.diag.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.RunStatus;
import io.diag.evidence.repository.RunRepository;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;

public class StartupJanitor {
    private static final Logger log = LoggerFactory.getLogger(StartupJanitor.class);
    private static final Pattern RUN_ID = Pattern.compile("^\\d{8}-\\d{6}-.*");
    private final ObjectMapper objectMapper;
    private final RunRepository runRepository;

    public StartupJanitor(ObjectMapper objectMapper, RunRepository runRepository) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.objectMapper = objectMapper;
        this.runRepository = runRepository;
    }

    public void cleanup(String activeRunId){


        List<String> projects = listComposeProjects();
        for(String name:orphans(projects, activeRunId)){
            log.warn("[janitor] tearing down orphan compose project: {}", name);
            tearDown(name);
        }

        // cross-check (spec 5.10 step 3): a RUNNING row with no live compose
        // project is a killed run waiting for resume — surfaced, never touched
        runRepository.findFirstByStatus(RunStatus.RUNNING.name()).ifPresent(run -> {
            if(!projects.contains(run.getId())){
                log.warn("[janitor] RUNNING run {} has no live compose project — killed run waiting for resume", run.getId());
            }
        });
    }

    private void tearDown(String name) {
        try {
            int exit =  new ProcessBuilder("docker","compose","-p",name,"down","-v","--remove-orphans")
                    .inheritIO().start().waitFor();
            if(exit != 0){
                log.warn("[janitor] compose down exited {} for {}, trying docker rm fallback", exit, name);
                dockerRmFallback(name);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted tearing down " + name, e);
        } catch (IOException e) {
            log.warn("[janitor] compose down failed for {}, trying docker rm fallback", name, e);
            dockerRmFallback(name);
        }
    }

    private void dockerRmFallback(String project) {
        try {
            Process ps = new ProcessBuilder("docker", "ps", "-aq",
                    "--filter", "label=com.docker.compose.project=" + project)
                    .start();
            String ids = new String(ps.getInputStream().readAllBytes()).trim();
            ps.waitFor();
            if (!ids.isBlank()) {
                for (String id : ids.split("\\s+")) {
                    new ProcessBuilder("docker", "rm", "-f", id).inheritIO().start().waitFor();
                }
            }
            new ProcessBuilder("docker", "network", "rm", project + "_default")
                    .inheritIO().start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted in docker rm fallback for " + project, e);
        } catch (IOException e) {
            // fallback already failed — log and move on, don't crash startup
            log.warn("[janitor] docker rm fallback failed for {}", project, e);
        }
    }


    /** Pure classification, testable without docker: an orphan matches the
     *  run-id pattern, is not the active run, and is not the always-on
     *  evidence DB project. A null activeRunId (fresh start) spares nothing
     *  but diag-evidence. */
    public static List<String> orphans(List<String> projectNames, String activeRunId) {
        List<String> result = new ArrayList<>();
        for(String name:projectNames){
            if(isOrphan(name, activeRunId)) result.add(name);
        }
        return result;
    }

    private static boolean isOrphan(String name, String activeRunId) {
        if("diag-evidence".equals(name)) return false;
        if(Objects.equals(name, activeRunId)) return false;
        return RUN_ID.matcher(name).matches();
    }

    private List<String> listComposeProjects() {
        try {
            Process p = new ProcessBuilder("docker","compose","ls","--all","--format", "json").start();
            byte[] out = p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            if(exit != 0 ){
                log.warn("[janitor] docker compose ls exited {}", exit);
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for(JsonNode node: objectMapper.readTree(out)){
                String name = node.get("Name").asText(null);
                if(name != null){
                    names.add(name);
                }
            }
            return names;
        }
        catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted listing compose projects", e);
        }
        catch (IOException e){
            log.warn("[janitor] could not list compose projects", e);
            return List.of();
        }
    }
}
