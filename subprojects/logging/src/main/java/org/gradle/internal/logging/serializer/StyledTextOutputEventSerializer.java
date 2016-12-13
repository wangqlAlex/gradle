/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.serializer;

import com.google.common.base.Objects;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

import java.util.List;

public class StyledTextOutputEventSerializer extends AbstractSerializer<StyledTextOutputEvent> {
    private final Serializer<LogLevel> logLevelSerializer;
    private final Serializer<List<StyledTextOutputEvent.Span>> spanSerializer;

    public StyledTextOutputEventSerializer(Serializer<LogLevel> logLevelSerializer, Serializer<List<StyledTextOutputEvent.Span>> spanSerializer) {
        this.logLevelSerializer = logLevelSerializer;
        this.spanSerializer = spanSerializer;
    }

    @Override
    public void write(Encoder encoder, StyledTextOutputEvent event) throws Exception {
        encoder.writeLong(event.getTimestamp());
        encoder.writeString(event.getCategory());
        logLevelSerializer.write(encoder, event.getLogLevel());
        spanSerializer.write(encoder, event.getSpans());
    }

    @Override
    public StyledTextOutputEvent read(Decoder decoder) throws Exception {
        long timestamp = decoder.readLong();
        String category = decoder.readString();
        LogLevel logLevel = logLevelSerializer.read(decoder);
        List<StyledTextOutputEvent.Span> spans = spanSerializer.read(decoder);
        return new StyledTextOutputEvent(timestamp, category, logLevel, spans);
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        StyledTextOutputEventSerializer rhs = (StyledTextOutputEventSerializer) obj;
        return Objects.equal(logLevelSerializer, rhs.logLevelSerializer)
            && Objects.equal(spanSerializer, rhs.spanSerializer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), logLevelSerializer, spanSerializer);
    }
}

