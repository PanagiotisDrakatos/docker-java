package com.github.dockerjava.netty;


import com.github.dockerjava.test.serdes.JSONTestHelper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;

/**
 * @author Alexander Koshevoy
 */
public class NettyWebTargetTest {
    @Mock
    private ChannelProvider channelProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void verifyImmutability() {
        NettyWebTarget emptyWebTarget = new NettyWebTarget(JSONTestHelper.getMapper(), channelProvider, "DUMMY");

        NettyWebTarget initWebTarget = emptyWebTarget.path("/containers/{id}/attach").resolveTemplate("id", "d03da378b592")
            .queryParam("logs", "true");

        NettyWebTarget anotherWebTarget = emptyWebTarget.path("/containers/{id}/attach")
            .resolveTemplate("id", "2cfada4e3c07").queryParam("stdin", "true");

        assertEquals(emptyWebTarget, new NettyWebTarget(JSONTestHelper.getMapper(), channelProvider, "DUMMY"));

        assertEquals(initWebTarget, new NettyWebTarget(JSONTestHelper.getMapper(), channelProvider, "DUMMY").path("/containers/d03da378b592/attach")
            .queryParam("logs", "true"));

        assertEquals(anotherWebTarget, new NettyWebTarget(JSONTestHelper.getMapper(), channelProvider, "DUMMY").path("/containers/2cfada4e3c07/attach")
            .queryParam("stdin", "true"));
    }
}
