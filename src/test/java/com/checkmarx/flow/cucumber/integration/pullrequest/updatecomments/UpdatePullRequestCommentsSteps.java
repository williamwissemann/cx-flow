package com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments;

import com.checkmarx.flow.CxFlowApplication;
import com.checkmarx.flow.config.FlowProperties;
import com.checkmarx.flow.config.GitHubProperties;
import com.checkmarx.flow.controller.ADOController;
import com.checkmarx.flow.controller.GitHubController;
import com.checkmarx.flow.dto.ControllerRequest;
import com.checkmarx.flow.dto.RepoComment;
import com.checkmarx.flow.dto.ScanRequest;
import com.checkmarx.flow.dto.azure.*;
import com.checkmarx.flow.dto.github.PullEvent;
import com.checkmarx.flow.dto.github.Repository;
import com.checkmarx.flow.dto.github.*;
import com.checkmarx.flow.service.ADOService;
import com.checkmarx.flow.service.GitHubService;
import com.checkmarx.flow.service.HelperService;
import com.checkmarx.flow.service.PullRequestCommentsHelper;
import com.checkmarx.sdk.config.CxProperties;
import com.checkmarx.sdk.config.ScaProperties;
import com.checkmarx.sdk.dto.ScanResults;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;


@SpringBootTest(classes = {CxFlowApplication.class})
@Slf4j
public class UpdatePullRequestCommentsSteps {


    public static final int COMMENTS_POLL_INTERVAL = 5;
    private static final String GIT_PROJECT_NAME = "vb_test_pr_comments";
    private static final String GITHUB_PR_BASE_URL = "https://api.github.com/repos/cxflowtestuser/" + GIT_PROJECT_NAME;
    private static final String GITHUB_PR_ID = "6";
    private static final String ADO_PR_ID = "69";
    public static final String PULL_REQUEST_COMMENTS_URL = GITHUB_PR_BASE_URL + "/issues/"+ GITHUB_PR_ID + "/comments";
    private static final String GIT_URL = "https://github.com/cxflowtestuser/" + GIT_PROJECT_NAME;
    private static final String ADO_PR_COMMENTS_URL = "https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66/pullRequests/" + ADO_PR_ID + "/threads";
    private final GitHubService gitHubService;
    private final ADOService adoService;
    private GitHubController gitHubControllerSpy;
    private ADOController adoControllerSpy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubProperties gitHubProperties;
    private final HelperService helperService;
    private ScanResults scanResultsToInject;
    private SourceControlType sourceControl;
    private FlowProperties flowProperties;
    private CxProperties cxProperties;
    private String branchAdo;
    private String branchGitHub;
    private ScannerType scannerType;
    private final ScaProperties scaProperties;


    public UpdatePullRequestCommentsSteps(GitHubService gitHubService, GitHubProperties gitHubProperties, GitHubController gitHubController, ADOService adoService, ADOController adoController, FlowProperties flowProperties, CxProperties cxProperties, ScaProperties scaProperties) {
        this.helperService = mock(HelperService.class);
        this.gitHubService = gitHubService;
        this.gitHubProperties = gitHubProperties;
        this.gitHubControllerSpy = Mockito.spy(gitHubController);
        this.adoService = adoService;
        this.adoControllerSpy = Mockito.spy(adoController);
        this.flowProperties = flowProperties;
        this.cxProperties = cxProperties;
        this.scaProperties = scaProperties;

    }

    private void initSca() {
        scaProperties.setAppUrl("https://sca.scacheckmarx.com");
        scaProperties.setApiUrl("https://api.scacheckmarx.com");
        scaProperties.setAccessControlUrl("https://platform.checkmarx.net");
    }

    @Before
    public void initMocks() {
        initGitHubProperties();
        initSca();
        flowProperties.getBranches().add("udi-tests-2");
        flowProperties.setEnabledVulnerabilityScanners(Arrays.asList("sast"));
        initGitHubControllerSpy();
        initHelperServiceMock();
        setBranches();
    }

    private void setBranches() {
        branchAdo =  "udi-tests-2";
        branchGitHub = "pr-comments-tests";
    }

    @After
    public void cleanUp() throws IOException, InterruptedException {
        if (sourceControl.equals(SourceControlType.GITHUB)) {
            deleteGitHubComments();
        } else if (sourceControl.equals(SourceControlType.ADO)) {
            deleteADOComments();
        }
    }

    @Given("scanner is set to {string}")
    public void setScanner(String scanner) {
        if ("sast".equals(scanner)) {
            scannerType = ScannerType.SAST;
            flowProperties.setEnabledVulnerabilityScanners(Arrays.asList("sast"));
        } else if ("sca".equals(scanner)) {
            scannerType = ScannerType.SCA;
            flowProperties.setEnabledVulnerabilityScanners(Arrays.asList("sca"));
        } else if ("both".equals(scanner)) {
            scannerType = ScannerType.BOTH;
            flowProperties.setEnabledVulnerabilityScanners(Arrays.asList("sca", "sast"));
        }
        else {
            throw new IllegalArgumentException("Wrong scanner type: " + scanner);
        }
    }

    @Given("different filters configuration is set")
    public void setConfigAsCodeFilters() {
        gitHubProperties.setConfigAsCode("cx.config.high.json");
    }

    @Given("source control is GitHub")
    public void scGitHub() {
        sourceControl = SourceControlType.GITHUB;
    }

    @Given("source control is ADO")
    public void scAdo() {
        sourceControl = SourceControlType.ADO;
    }


    @Given("no comments on pull request")
    public void deletePRComments() throws IOException, InterruptedException {
        if (sourceControl.equals(SourceControlType.GITHUB)) {
            deleteGitHubComments();
        } else if (sourceControl.equals(SourceControlType.ADO)) {
            deleteADOComments();
        }
    }

    private void deleteADOComments() throws IOException {
        List<RepoComment> adoComments = getRepoComments();
        for (RepoComment rc: adoComments) {
            adoService.deleteComment(rc.getCommentUrl());
        }
    }

    private void deleteGitHubComments() throws IOException {
        List<RepoComment> comments = getRepoComments();
        for (RepoComment comment: comments) {
            gitHubService.deleteComment(comment.getCommentUrl(), getBasicRequest());
        }
    }

    private List<RepoComment> getRepoComments() throws IOException {
        if (sourceControl.equals(SourceControlType.GITHUB)) {
            return gitHubService.getComments(getBasicRequest());
        }
        else if (sourceControl.equals(SourceControlType.ADO)){
            return adoService.getComments(ADO_PR_COMMENTS_URL);
        }
        throw new IllegalArgumentException("Unknown source control: " + sourceControl);
    }

    @When("pull request arrives to CxFlow")
    public void sendPullRequest() {
        if (sourceControl.equals(SourceControlType.GITHUB)) {
            buildGitHubPullRequest();
        } else if (sourceControl.equals(SourceControlType.ADO)) {
            buildADOPullRequestEvent();
        }
    }

    @Then("Wait for comments")
    public void waitForNewComments() {
        int minutesToWait = scannerType == ScannerType.BOTH ? 3 : 2;
        Awaitility.await().atMost(Duration.ofMinutes(minutesToWait)).pollInterval(Duration.ofSeconds(COMMENTS_POLL_INTERVAL)).until(this::areThereCommentsAtAll);
    }

    @Then("verify new comments")
    public void verifyNewComments() throws IOException {
        int expectedNumOfComments = getExpectedNumOfNewComments();
        List<RepoComment> comments = getRepoComments();
        Assert.assertEquals("Wrong number of comments", expectedNumOfComments, comments.size());
        comments.stream().forEach(c -> Assert.assertTrue("Comment is not new (probably updated", isCommentNew(c)));

    }

    private int getExpectedNumOfNewComments() {
        switch (scannerType) {
            case SCA:
                return 1;
            case SAST:
            case BOTH:
                return 2;
        }
        throw  new RuntimeException("Wrong scanner type");
    }

    @Then("Wait for updated comment")
    public void waitForUpdatedComment() {
        Awaitility.await().pollInterval(Duration.ofSeconds(COMMENTS_POLL_INTERVAL)).atMost(Duration.ofSeconds(125)).until(this::isThereUpdatedComment);
    }

    private boolean areThereCommentsAtAll() throws IOException {
        List<RepoComment> comments = getRepoComments();
        if (scannerType == ScannerType.SCA) {
            if (comments.size() < 1) {
                return false;
            }
        } else if (scannerType == ScannerType.SAST || scannerType == ScannerType.BOTH) {
            if(comments.size() <= 1) {
                return false;
            }
        } else {
            throw new IllegalArgumentException("Wrong Scanner Type: " + scannerType.name());
        }
        return areThereCorrectComments(comments, scannerType);
    }

    private boolean areThereCorrectComments(List<RepoComment> comments, ScannerType sct){

        if (sct.equals(ScannerType.BOTH)) {
            boolean foundScaAndSast = false;
            boolean foundScanStarted = false;
            for (RepoComment comment : comments) {
                if (PullRequestCommentsHelper.isScanStartedComment(comment.getComment())) {
                    foundScanStarted = true;
                } else if (PullRequestCommentsHelper.isSastAndScaComment(comment.getComment())) {
                    foundScaAndSast = true;
                }
            }
            return foundScaAndSast && foundScanStarted;
        }
        else if (sct.equals(ScannerType.SAST)) {
            boolean foundSast = false;
            boolean foundScanStarted = false;
            for (RepoComment comment : comments) {
                if (PullRequestCommentsHelper.isSastFindingsComment(comment.getComment())) {
                    foundSast = true;
                } else if (PullRequestCommentsHelper.isScanStartedComment(comment.getComment())) {
                    foundScanStarted = true;
                }
            }
            return foundSast && foundScanStarted;
        }
        else if (sct.equals(ScannerType.SCA)) {
            for (RepoComment comment : comments) {
                if (PullRequestCommentsHelper.isScaComment(comment.getComment())) {
                    return true;
                }
            }
        }
        throw new IllegalArgumentException("Wrong scanner type: " + sct.name());
    }

    private boolean isThereUpdatedComment() throws IOException {
        List<RepoComment> comments = getRepoComments();
        for (RepoComment comment: comments) {
            if (isCommentUpdated(comment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommentNew(RepoComment comment) {
        return comment.getUpdateTime().equals(comment.getCreatedAt());
    }

    private boolean isCommentUpdated(RepoComment comment) {
        return comment.getUpdateTime().after(comment.getCreatedAt());
    }

    private void initGitHubControllerSpy() {
        doNothing().when(gitHubControllerSpy).verifyHmacSignature(any(), any(), any());
    }

    private void initHelperServiceMock() {
        when(helperService.isBranch2Scan(any(), anyList())).thenReturn(true);
        when(helperService.getShortUid()).thenReturn("123456");
    }

    private ScanRequest getBasicRequest() {
        return ScanRequest.builder()
                .mergeNoteUri(PULL_REQUEST_COMMENTS_URL)
                .build();
    }

    private void initGitHubProperties() {
        this.gitHubProperties.setCxSummary(false);
        this.gitHubProperties.setFlowSummary(false);
        this.gitHubProperties.setUrl(GIT_URL);
        this.gitHubProperties.setWebhookToken("1234");
        this.gitHubProperties.setApiUrl("https://api.github.com/repos");
    }


    public void buildGitHubPullRequest() {
        PullEvent pullEvent = new PullEvent();
        Repository repo = new Repository();
        repo.setName("vb_test_udi");

        repo.setCloneUrl(gitHubProperties.getUrl());
        Owner owner = new Owner();
        owner.setName("");
        owner.setLogin("cxflowtestuser");
        repo.setOwner(owner);
        pullEvent.setRepository(repo);
        pullEvent.setAction("opened");
        PullRequest pullRequest = new PullRequest();
        pullRequest.setIssueUrl("");
        Head headBranch = new Head();
        headBranch.setRef(branchGitHub);

        pullRequest.setHead(headBranch);
        pullRequest.setBase(new Base());
        pullRequest.setStatusesUrl("");
        pullRequest.setIssueUrl(GITHUB_PR_BASE_URL + "/issues/" + GITHUB_PR_ID);

        pullEvent.setPullRequest(pullRequest);

        try {
            String pullEventStr = mapper.writeValueAsString(pullEvent);
            ControllerRequest controllerRequest = new ControllerRequest();
            controllerRequest.setApplication("VB");
            controllerRequest.setBranch(Arrays.asList(branchGitHub));
            controllerRequest.setProject("VB");
            controllerRequest.setTeam("\\CxServer\\SP");
            controllerRequest.setPreset("default");
            controllerRequest.setIncremental(false);
            gitHubControllerSpy.pullRequest(
                    pullEventStr,
                    "SIGNATURE",
                    "CX",
                    controllerRequest
                    );

        } catch (JsonProcessingException e) {
            fail("Unable to parse " + pullEvent.toString());
        }
    }


    public void buildADOPullRequestEvent() {
        com.checkmarx.flow.dto.azure.PullEvent pullEvent = new com.checkmarx.flow.dto.azure.PullEvent();
        pullEvent.setEventType("git.pullrequest.updated");
        pullEvent.setId("4519989c-c157-4bf8-9651-e94b8d0fca27");
        pullEvent.setSubscriptionId("25aa3b80-54ed-4b26-976a-b74f94940852");
        pullEvent.setPublisherId("tfs");

        Project_ project = new Project_();
        project.setId("3172109f-8bcb-4f21-a8f7-4f94d4a825b0");
        project.setBaseUrl("https://dev.azure.com/OrgName/");

        ResourceContainers resourceContainers = new ResourceContainers();
        resourceContainers.setProject(project);

        pullEvent.setResourceContainers(resourceContainers);
        Resource resource = new Resource();
        resource.setStatus("active");
        resource.setSourceRefName("refs/heads/master");
        resource.setTargetRefName("refs/heads/udi-tests-2");
        resource.setUrl("https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66/pullRequests/" + ADO_PR_ID);
        com.checkmarx.flow.dto.azure.Repository repo = new com.checkmarx.flow.dto.azure.Repository();
        repo.setId("a89a9d2f-ab67-4bda-9c56-a571224c2c66");
        repo.setName("AdoPullRequestTests");
        repo.setUrl("https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66");
        repo.setRemoteUrl("https://CxNamespace@dev.azure.com/CxNamespace/AdoPullRequestTests/_git/AdoPullRequestTests");
        repo.setSshUrl("git@ssh.dev.azure.com:v3/CxNamespace/AdoPullRequestTests/AdoPullRequestTests");
        repo.setWebUrl("https://dev.azure.com/CxNamespace/AdoPullRequestTests/_git/AdoPullRequestTests");
        Project pr = new Project();
        pr.setId("d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16");
        pr.setName("AdoPullRequestTests");
        repo.setProject(pr);
        resource.setRepository(repo);

        pullEvent.setResource(resource);
        ControllerRequest controllerRequest = new ControllerRequest();
        controllerRequest.setProject("AdoPullRequestTests-master");
        controllerRequest.setTeam("\\CxServer\\SP");
        AdoDetailsRequest adoRequest = new AdoDetailsRequest();
        adoControllerSpy.pullRequest(pullEvent,"Basic Y3hmbG93OjEyMzQ=", null, controllerRequest, adoRequest);
    }

    private class ScanResultsAnswerer implements Answer<ScanResults> {
        @Override
        public ScanResults answer(InvocationOnMock invocation) {
            return scanResultsToInject;
        }
    }

    enum SourceControlType {
        GITHUB,
        ADO
    }

    enum ScannerType {
        SAST,
        SCA,
        BOTH;
    }
}
