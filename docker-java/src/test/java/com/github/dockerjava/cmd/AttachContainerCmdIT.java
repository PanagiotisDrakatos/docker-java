package com.github.dockerjava.cmd;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.dockerjava.core.DockerRule.DEFAULT_IMAGE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Kanstantsin Shautsou
 */
public class AttachContainerCmdIT extends CmdIT {
    private static final Logger LOG = LoggerFactory.getLogger(AttachContainerCmdIT.class);


    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void attachContainerWithStdin() throws Exception {
        DockerClient dockerClient = dockerRule.getClient();

        String snippet = "hello world";

        CreateContainerResponse container = dockerClient.createContainerCmd("busybox")
            .withCmd("/bin/sh", "-c", "read line && echo $line")
            .withTty(false)
            .withAttachStdin(true)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .withStdinOpen(true)
            .exec();

        LOG.info("Created container: {}", container.toString());
        assertThat(container.getId(), not(is(emptyString())));

        AttachContainerTestCallback callback = new AttachContainerTestCallback() {
            @Override
            public void onNext(Frame frame) {
                assertEquals(StreamType.STDOUT, frame.getStreamType());
                super.onNext(frame);
            }
        };

        try (
            PipedOutputStream out = new PipedOutputStream();
            PipedInputStream in = new PipedInputStream(out)
        ) {
            dockerClient.attachContainerCmd(container.getId())
                .withStdErr(true)
                .withStdOut(true)
                .withFollowStream(true)
                .withStdIn(in)
                .exec(callback);

            assertTrue("Processing of the response should start shortly after executing `attachContainerCmd`",
                callback.awaitStarted(5, SECONDS));

            dockerClient.startContainerCmd(container.getId()).exec();

            out.write((snippet + "\n").getBytes());
            out.flush();

            callback.awaitCompletion(15, SECONDS);
            callback.close();
        }

        assertThat(callback.toString(), containsString(snippet));
    }

    @Test
    public void attachContainerWithoutTTY() throws Exception {
        DockerClient dockerClient = dockerRule.getClient();

        String snippet = "hello world";

        CreateContainerResponse container = dockerClient.createContainerCmd(DEFAULT_IMAGE)
            .withCmd("echo", snippet)
            .withTty(false)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec();

        LOG.info("Created container: {}", container.toString());
        assertThat(container.getId(), not(is(emptyString())));

        AttachContainerTestCallback callback = new AttachContainerTestCallback() {
            @Override
            public void onNext(Frame frame) {
                assertThat(frame.getStreamType(), equalTo(StreamType.STDOUT));
                super.onNext(frame);
            }
        };

        dockerClient.attachContainerCmd(container.getId())
            .withStdErr(true)
            .withStdOut(true)
            .withFollowStream(true)
            .withLogs(true)
            .exec(callback);

        assertTrue("Processing of the response should start shortly after executing `attachContainerCmd`",
            callback.awaitStarted(5, SECONDS));

        dockerClient.startContainerCmd(container.getId()).exec();

        callback.awaitCompletion(30, TimeUnit.SECONDS);
        callback.close();

        assertThat(callback.toString(), containsString(snippet));
    }

    @Test
    public void attachContainerWithTTY() throws Exception {
        DockerClient dockerClient = dockerRule.getClient();

        File baseDir = new File(Thread.currentThread().getContextClassLoader()
            .getResource("attachContainerTestDockerfile").getFile());

        String imageId = dockerRule.buildImage(baseDir);

        CreateContainerResponse container = dockerClient.createContainerCmd(imageId)
            .withTty(true)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec();

        LOG.info("Created container: {}", container.toString());
        assertThat(container.getId(), not(is(emptyString())));


        AttachContainerTestCallback callback = new AttachContainerTestCallback() {
            @Override
            public void onNext(Frame frame) {
                assertThat(frame.getStreamType(), equalTo(StreamType.RAW));
                super.onNext(frame);
            }
        };

        dockerClient.attachContainerCmd(container.getId())
            .withStdErr(true)
            .withStdOut(true)
            .withFollowStream(true)
            .exec(callback);

        assertTrue("Processing of the response should start shortly after executing `attachContainerCmd`",
            callback.awaitStarted(5, SECONDS));

        dockerClient.startContainerCmd(container.getId()).exec();

        callback.awaitCompletion(15, TimeUnit.SECONDS);
        callback.close();

        LOG.debug("log: {}", callback.toString());

        // HexDump.dump(collectFramesCallback.toString().getBytes(), 0, System.out, 0);
        assertThat(callback.toString(), containsString("stdout\r\nstderr"));
    }

    /**
     * {@link ResultCallback#onComplete()} should be called immediately after
     * container exit. It was broken for Netty and TLS connection.
     */
    @Test
    public void attachContainerClosesStdoutWhenContainerExits() throws Exception {
        DockerClient dockerClient = dockerRule.getClient();

        CreateContainerResponse container = dockerClient.createContainerCmd(DEFAULT_IMAGE)
            .withCmd("echo", "hello")
            .withTty(false)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec();
        LOG.info("Created container: {}", container.toString());

        CountDownLatch gotLine = new CountDownLatch(1);
        try (
            ResultCallback.Adapter<Frame> resultCallback = dockerClient.attachContainerCmd(container.getId())
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame item) {
                        LOG.info("Got frame: {}", item);
                        if (item.getStreamType() == StreamType.STDOUT) {
                            gotLine.countDown();
                        }
                        super.onNext(item);
                    }

                    @Override
                    public void onComplete() {
                        LOG.info("On complete");
                        super.onComplete();
                    }
                })
        ) {
            resultCallback.awaitStarted(5, SECONDS);
            LOG.info("Attach started");

            dockerClient.startContainerCmd(container.getId()).exec();
            LOG.info("Container started");

            assertTrue("Should get first line quickly after the start", gotLine.await(15, SECONDS));

            resultCallback.awaitCompletion(5, SECONDS);
        }
    }

    public static class AttachContainerTestCallback extends ResultCallback.Adapter<Frame> {
        private final StringBuffer log = new StringBuffer();

        @Override
        public void onNext(Frame item) {
            log.append(new String(item.getPayload()));
            super.onNext(item);
        }

        @Override
        public String toString() {
            return log.toString();
        }
    }
}
