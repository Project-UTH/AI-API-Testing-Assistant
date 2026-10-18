package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ErrorKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 16 - phân loại theo LOẠI exception (kể cả bị bọc nhiều lớp), không theo chuỗi thông báo. */
class ErrorKindClassifierTest {

    @Test
    void classify_timeoutConnectionDns() {
        assertThat(ErrorKindClassifier.classify(new SocketTimeoutException("Read timed out"))).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(ErrorKindClassifier.classify(new ConnectException("Connection refused"))).isEqualTo(ErrorKind.CONNECTION);
        assertThat(ErrorKindClassifier.classify(new SocketException("Connection reset"))).isEqualTo(ErrorKind.CONNECTION);
        assertThat(ErrorKindClassifier.classify(new UnknownHostException("no-such-host"))).isEqualTo(ErrorKind.DNS);
    }

    @Test
    void classify_walksTheCauseChainAndIgnoresMessageText() {
        // Thông báo ngoài cùng cố tình gây hiểu nhầm: chữ "timeout" nhưng nguyên nhân gốc là không phân giải được host.
        Throwable wrapped = new RuntimeException("timeout while calling", new IOException("io", new UnknownHostException("x")));

        assertThat(ErrorKindClassifier.classify(wrapped)).isEqualTo(ErrorKind.DNS);
    }

    @Test
    void classify_apacheClassesMatchedBySimpleNameWithoutDependingOnThem() {
        class ConnectTimeoutException extends IOException {
        }
        class NoHttpResponseException extends IOException {
        }

        assertThat(ErrorKindClassifier.classify(new ConnectTimeoutException())).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(ErrorKindClassifier.classify(new NoHttpResponseException())).isEqualTo(ErrorKind.CONNECTION);
    }

    @Test
    void classify_unknownErrorIsOtherNeverNull() {
        assertThat(ErrorKindClassifier.classify(new IllegalStateException("ssl handshake"))).isEqualTo(ErrorKind.OTHER);
        assertThat(ErrorKindClassifier.classify(null)).isEqualTo(ErrorKind.OTHER);
    }

    @Test
    void classify_selfReferencingCauseDoesNotLoopForever() {
        Throwable selfCause = new RuntimeException("x") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(ErrorKindClassifier.classify(selfCause)).isEqualTo(ErrorKind.OTHER);
    }
}
