package app.swifttv.player;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Collections;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackErrorsTest {
    private PlaybackException wrapped(Throwable cause, int code) {
        return new PlaybackException("https://private.example/token?secret=private", new IOException("wrapper", cause), code);
    }
    @Test public void reportsHttpStatusWithoutLeakingBodyHeadersOrUrl() {
        // DataSpec is irrelevant to classification; null avoids Android Uri calls in this JVM test.
        HttpDataSource.InvalidResponseCodeException http = new HttpDataSource.InvalidResponseCodeException(
            403, "private-response", null, Collections.singletonMap("Authorization", Collections.singletonList("private-token")),
            null, "private-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String message = PlaybackErrors.INSTANCE.describe(wrapped(http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
        assertTrue(message.contains("HTTP 403"));
        assertFalse(message.contains("private"));
        assertFalse(message.contains("Authorization"));
        assertFalse(message.contains("https://"));
    }
    @Test public void certificateCauseTakesPrecedenceOverGenericNetworkCode() {
        SSLHandshakeException tls = new SSLHandshakeException("private-host");
        tls.initCause(new CertificateException("private certificate"));
        String message = PlaybackErrors.INSTANCE.describe(wrapped(tls, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));
        assertTrue(message.contains("证书校验失败"));
        assertFalse(message.contains("private"));
    }
    @Test public void distinguishesDnsTimeoutResetAndConnectionRefusal() {
        assertTrue(PlaybackErrors.INSTANCE.describe(wrapped(new UnknownHostException("private"), 2001)).contains("域名解析"));
        assertTrue(PlaybackErrors.INSTANCE.describe(wrapped(new SocketTimeoutException("private"), 2001)).contains("超时"));
        assertTrue(PlaybackErrors.INSTANCE.describe(wrapped(new SocketException("Connection reset"), 2001)).contains("重置"));
        assertTrue(PlaybackErrors.INSTANCE.describe(wrapped(new ConnectException("private"), 2001)).contains("无法连接"));
    }
    @Test public void usesDecoderCodeOnlyForDecoderFailures() {
        assertTrue(PlaybackErrors.INSTANCE.describe(wrapped(null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)).contains("解码格式"));
        assertTrue(PlaybackErrors.INSTANCE.describe(wrapped(null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)).contains("数据格式"));
    }
    @Test public void boundedTraversalHandlesCyclicCauseChains() {
        IOException one = new IOException("private-one");
        IOException two = new IOException("private-two");
        one.initCause(two); two.initCause(one);
        assertEquals("无法读取线路数据", PlaybackErrors.INSTANCE.describe(one));
    }

    @Test public void routeEvidenceIncludesReachabilityAndDenials() {
        HttpDataSource.InvalidResponseCodeException denied = new HttpDataSource.InvalidResponseCodeException(
            403, "", null, Collections.emptyMap(), null, new byte[0]);
        assertTrue(PlaybackErrors.INSTANCE.isReachabilityFailure(denied));
        assertTrue(PlaybackErrors.INSTANCE.isReachabilityFailure(new UnknownHostException()));
        assertTrue(PlaybackErrors.INSTANCE.isReachabilityFailure(
            wrapped(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)));
    }
    @Test public void missingChannelsDecoderErrorsAndTlsDoNotChangeTheRoute() {
        HttpDataSource.InvalidResponseCodeException missing = new HttpDataSource.InvalidResponseCodeException(
            404, "", null, Collections.emptyMap(), null, new byte[0]);
        assertFalse(PlaybackErrors.INSTANCE.isReachabilityFailure(missing));
        assertFalse(PlaybackErrors.INSTANCE.isReachabilityFailure(
            wrapped(null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)));
        assertFalse(PlaybackErrors.INSTANCE.isReachabilityFailure(
            wrapped(new SSLHandshakeException(""), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)));
    }
}
