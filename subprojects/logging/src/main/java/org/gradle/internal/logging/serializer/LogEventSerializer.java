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
import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

public class LogEventSerializer extends AbstractSerializer<LogEvent> {
    private final Serializer<Throwable> throwableSerializer;
    private final Serializer<LogLevel> logLevelSerializer;

    public LogEventSerializer(Serializer<LogLevel> logLevelSerializer, Serializer<Throwable> throwableSerializer) {
        this.logLevelSerializer = logLevelSerializer;
        this.throwableSerializer = throwableSerializer;
    }

    @Override
    public void write(Encoder encoder, LogEvent event) throws Exception {
        encoder.writeLong(event.getTimestamp());
        encoder.writeString(event.getCategory());
        logLevelSerializer.write(encoder, event.getLogLevel());
        encoder.writeString(event.getMessage());
        throwableSerializer.write(encoder, event.getThrowable());
    }

    @Override
    public LogEvent read(Decoder decoder) throws Exception {
        long timestamp = decoder.readLong();
        String category = decoder.readString();
        LogLevel logLevel = logLevelSerializer.read(decoder);
        String message = decoder.readString();
        Throwable throwable = throwableSerializer.read(decoder);
        return new LogEvent(timestamp, category, logLevel, message, throwable);
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        LogEventSerializer rhs = (LogEventSerializer) obj;
        return Objects.equal(logLevelSerializer, rhs.logLevelSerializer)
            && Objects.equal(throwableSerializer, rhs.throwableSerializer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), logLevelSerializer, throwableSerializer);
    }
}
