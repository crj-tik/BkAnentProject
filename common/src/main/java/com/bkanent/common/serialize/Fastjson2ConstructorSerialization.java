package com.bkanent.common.serialize;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * fastjson2 variant without WriteClassName/FieldBased. Dubbo's built-in fastjson2 writes both,
 * and the generated ObjectReaderNoneDefaultConstructor path crashes decoding record payloads
 * with list fields when a ContextAutoTypeBeforeHandler is installed (fastjson2 2.0.52..2.0.59,
 * "array not support input TYPED_ANY", KI-113 follow-up). RPC arguments are decoded through
 * their declared method types, so class-name tags are unnecessary on this wire; plain
 * constructor/property JSON round-trips the same contract graph. There are no class-name tags
 * on this wire, so an untyped read can only produce plain containers, never an attacker-chosen
 * class; typed reads go through the declared method signature.
 */
public final class Fastjson2ConstructorSerialization implements Serialization {
    private static final JSONWriter.Feature[] WRITE = {
            JSONWriter.Feature.ErrorOnNoneSerializable,
            JSONWriter.Feature.WriteNulls,
            JSONWriter.Feature.NotWriteHashMapArrayListClassName};

    @Override public byte getContentTypeId() { return 27; }

    @Override public String getContentType() { return "x-application/fastjson2-ctor"; }

    @Override public ObjectOutput serialize(URL url, OutputStream output) throws IOException {
        return new ConstructorObjectOutput(output);
    }

    @Override public ObjectInput deserialize(URL url, InputStream input) throws IOException {
        return new ConstructorObjectInput(input);
    }

    static final class ConstructorObjectOutput implements ObjectOutput {
        private final OutputStream output;
        private boolean closed;
        ConstructorObjectOutput(OutputStream output) { this.output = output; }

        @Override public void writeBool(boolean value) throws IOException { writeObject(value); }
        @Override public void writeByte(byte value) throws IOException { writeObject(value); }
        @Override public void writeShort(short value) throws IOException { writeObject(value); }
        @Override public void writeInt(int value) throws IOException { writeObject(value); }
        @Override public void writeLong(long value) throws IOException { writeObject(value); }
        @Override public void writeFloat(float value) throws IOException { writeObject(value); }
        @Override public void writeDouble(double value) throws IOException { writeObject(value); }
        @Override public void writeUTF(String value) throws IOException { writeObject(value); }
        @Override public void writeBytes(byte[] value) throws IOException { writeObject(value); }
        @Override public void writeBytes(byte[] value, int offset, int length) throws IOException {
            writeObject(Arrays.copyOfRange(value, offset, offset + length));
        }
        @Override public void writeObject(Object value) throws IOException {
            ensureOpen();
            try { output.write(JSON.toJSONBytes(value, WRITE)); }
            catch (RuntimeException failure) { throw new IOException(failure); }
        }
        @Override public void flushBuffer() throws IOException { ensureOpen(); output.flush(); }
        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("fastjson2ctor output already flushed");
        }
        void markClosed() { closed = true; }
    }

    static final class ConstructorObjectInput implements ObjectInput {
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final InputStream input;
        private JSONReader reader;
        ConstructorObjectInput(InputStream input) { this.input = input; }

        private JSONReader reader() throws IOException {
            if (reader == null) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = input.read(chunk)) >= 0) buffer.write(chunk, 0, read);
                reader = JSONReader.of(buffer.toByteArray());
            }
            return reader;
        }

        @Override public boolean readBool() throws IOException { return read(Boolean.class); }
        @Override public byte readByte() throws IOException { return read(Byte.class); }
        @Override public short readShort() throws IOException { return read(Short.class); }
        @Override public int readInt() throws IOException { return read(Integer.class); }
        @Override public long readLong() throws IOException { return read(Long.class); }
        @Override public float readFloat() throws IOException { return read(Float.class); }
        @Override public double readDouble() throws IOException { return read(Double.class); }
        @Override public String readUTF() throws IOException { return read(String.class); }
        @Override public byte[] readBytes() throws IOException { return read(byte[].class); }
        @Override public Object readObject() throws IOException {
            return read(Object.class);
        }
        @Override public <T> T readObject(Class<T> type) throws IOException {
            return read(type);
        }
        @Override public <T> T readObject(Class<T> type, Type generic) throws IOException {
            return read(generic != null ? generic : type);
        }
        private <T> T read(Type type) throws IOException {
            try {
                return reader().read(type);
            } catch (IOException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new IOException(failure);
            }
        }
    }
}
