package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ErrorKind;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Module 16 - phân loại exception khi gọi target API thành {@link ErrorKind}, duyệt cả chuỗi cause (thư viện
 * HTTP thường bọc exception gốc). Khớp theo LOẠI exception, không theo chuỗi thông báo (mỗi thư viện/JDK viết
 * một kiểu). Lớp của Apache HttpClient khớp theo tên đơn giản để không phụ thuộc trực tiếp vào thư viện đó.
 */
public final class ErrorKindClassifier {

    private static final int MAX_CAUSE_DEPTH = 10;

    private ErrorKindClassifier() {
    }

    public static ErrorKind classify(Throwable error) {
        ErrorKind found = null;
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            ErrorKind kind = classifySingle(current);
            // DNS/TIMEOUT/CONNECTION cụ thể hơn OTHER: lấy kết quả cụ thể đầu tiên trong chuỗi cause.
            if (kind != ErrorKind.OTHER) {
                return kind;
            }
            found = ErrorKind.OTHER;
            current = current.getCause() == current ? null : current.getCause();
        }
        return found == null ? ErrorKind.OTHER : found;
    }

    private static ErrorKind classifySingle(Throwable error) {
        String simpleName = error.getClass().getSimpleName();
        if (error instanceof UnknownHostException) {
            return ErrorKind.DNS;
        }
        if (error instanceof SocketTimeoutException || "ConnectTimeoutException".equals(simpleName)
                || "HttpTimeoutException".equals(simpleName)) {
            return ErrorKind.TIMEOUT;
        }
        // SocketException gồm cả "Connection reset"; NoHttpResponseException: server đóng kết nối không trả gì.
        if (error instanceof ConnectException || error instanceof NoRouteToHostException || error instanceof SocketException
                || "HttpHostConnectException".equals(simpleName) || "NoHttpResponseException".equals(simpleName)) {
            return ErrorKind.CONNECTION;
        }
        return ErrorKind.OTHER;
    }
}
