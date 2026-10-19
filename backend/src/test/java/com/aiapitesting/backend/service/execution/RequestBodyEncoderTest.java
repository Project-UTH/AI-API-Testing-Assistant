package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.service.execution.RequestBodyEncoder.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyEncoderTest {

    @Test
    void jsonIsTheDefault_andWildcardOrJsonVariantsStayJson() {
        assertThat(RequestBodyEncoder.plan(null, null, "{\"a\":1}").kind()).isEqualTo(Kind.JSON);
        assertThat(RequestBodyEncoder.plan(null, "*/*", "{}").mediaType()).isEqualTo("application/json");
        assertThat(RequestBodyEncoder.plan(null, "application/vnd.api+json", "{}").mediaType()).isEqualTo("application/vnd.api+json");
    }

    @Test
    void headerBeatsSpec_formAndMultipartConvertFlatJsonFields() {
        RequestBodyEncoder.Plan form = RequestBodyEncoder.plan("application/x-www-form-urlencoded; charset=UTF-8", "application/json", "{\"a\":\"x\",\"n\":2,\"o\":{\"k\":1}}");
        assertThat(form.kind()).isEqualTo(Kind.FORM);
        assertThat(form.fields()).containsEntry("a", "x").containsEntry("n", "2").containsEntry("o", "{\"k\":1}");
        assertThat(RequestBodyEncoder.plan(null, "multipart/form-data", "{\"t\":\"v\"}").kind()).isEqualTo(Kind.MULTIPART);
    }

    @Test
    void formWithAlreadyEncodedBody_orXmlAndText_goRaw() {
        assertThat(RequestBodyEncoder.plan(null, "application/x-www-form-urlencoded", "a=1&b=2").kind()).isEqualTo(Kind.RAW);
        assertThat(RequestBodyEncoder.plan(null, "application/xml", "<a/>").kind()).isEqualTo(Kind.RAW);
        assertThat(RequestBodyEncoder.plan(null, "text/plain", "hi").mediaType()).isEqualTo("text/plain");
        assertThat(RequestBodyEncoder.flatFields("[1,2]")).isNull();
        assertThat(RequestBodyEncoder.flatFields("{broken")).isNull();
    }
}
