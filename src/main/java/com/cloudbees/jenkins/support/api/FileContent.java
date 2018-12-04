/*
 * The MIT License
 *
 * Copyright (c) 2013, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.cloudbees.jenkins.support.api;

import com.cloudbees.jenkins.support.SupportLogFormatter;
import com.cloudbees.jenkins.support.filter.ContentFilter;
import com.cloudbees.jenkins.support.filter.FilteredContent;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

/**
 * Content that is stored as a file on disk.
 *
 * @author Stephen Connolly
 */
public class FileContent extends FilteredContent {

    protected final File file;
    private final long maxSize;
    private final boolean isBinary;

    private final static String ENCODING = "UTF-8";

    public FileContent(String name, File file) {
        this(name, file, -1);
    }

    public FileContent(String name, File file, long maxSize) {
        super(name);
        this.file = file;
        this.maxSize = maxSize;
        this.isBinary = isBinary();
    }

    @Override
    public void writeTo(OutputStream os) throws IOException {
        try {
            InputStream is = getInputStream();
            if (maxSize == -1) {
                IOUtils.copy(is, os);
            } else {
                try {
                    IOUtils.copy(new TruncatedInputStream(is, maxSize), os);
                } finally {
                    is.close();
                }
            }
        } catch (FileNotFoundException e) {
            OutputStreamWriter osw = new OutputStreamWriter(os, ENCODING);
            try {
                PrintWriter pw = new PrintWriter(osw, true);
                try {
                    pw.println("--- WARNING: Could not attach " + file + " as it cannot currently be found ---");
                    pw.println();
                    SupportLogFormatter.printStackTrace(e, pw);
                } finally {
                    pw.flush();
                }
            } finally {
                osw.flush();
            }
        }
    }

    @Override
    public void writeTo(OutputStream os, ContentFilter filter) throws IOException {
        if (isBinary || filter == null) {
            writeTo(os);
        }

        try {
            if (maxSize == -1) {
                for (String s : Files.readAllLines(file.toPath())) {
                    String filtered = filter.filter(s);
                    IOUtils.write(filtered, os);
                }
            } else {
                try (TruncatedFileReader reader = new TruncatedFileReader(file, maxSize)) {
                    String s;
                    while ((s = reader.readLine()) != null) {
                        String filtered = filter.filter(s);
                        IOUtils.write(filtered, os);
                    }
                }
            }
        } catch (FileNotFoundException | NoSuchFileException e ) {
            OutputStreamWriter osw = new OutputStreamWriter(os, ENCODING);
            try {
                PrintWriter pw = new PrintWriter(osw, true);
                try {
                    pw.println("--- WARNING: Could not attach " + file + " as it cannot currently be found ---");
                    pw.println();
                    SupportLogFormatter.printStackTrace(e, pw);
                } finally {
                    pw.flush();
                }
            } finally {
                osw.flush();
            }
        }
    }

    /**
     * Instantiates the {@link InputStream} for the {@link #file}.
     * @return the {@link InputStream} for the {@link #file}.
     * @throws IOException if something goes wrong while creating the stream for reading #file.
     */
    protected InputStream getInputStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public long getTime() throws IOException {
        return file.lastModified();
    }

    // Check if the file is binary or not
    private boolean isBinary() {
        char[] c = new char[3];
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), ENCODING)) {
            int data = reader.read();

            for (int i = 0; i < 3; i++) {
                c[i] = (char) data;
                data = reader.read();
            }
        } catch (IOException e) {
            // If cannot be checked, then considered as binary, so we do not
            // read line by line
            return true;
        }

        StringBuffer type = new StringBuffer(Character.toString(c[0]));
        for (int i = 1; i < 2; i++) {
            type.append(c[i]);
        }

        return (!type.toString().matches("[_a-zA-Z0-9\\-\\.]*"));
    }


    /**
     * {@link InputStream} decorator that chops off the underlying stream at the
     * specified length
     *
     * @author Kohsuke Kawaguchi (from org.kohsuke.stapler)
     */
    private static final class TruncatedInputStream extends FilterInputStream {
        private long len;

        TruncatedInputStream(InputStream in, long len) {
            super(in);
            this.len = len;
        }

        @Override
        public int read() throws IOException {
            if (len <= 0) {
                return -1;
            }
            len--;
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int l) throws IOException {
            int toRead = (int) Math.min(l, len);
            if (toRead <= 0) {
                return -1;
            }

            int r = super.read(b, off, toRead);
            if (r > 0) {
                len -= r;
            }
            return r;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), len);
        }

        @Override
        public long skip(long n) throws IOException {
            long r = super.skip(Math.min(len, n));
            len -= r;
            return r;
        }
    }

    private static final class TruncatedFileReader extends BufferedReader {
        private long len;

        TruncatedFileReader(File file, long len) throws IOException {
            super(new InputStreamReader(new FileInputStream(file), ENCODING));
            this.len = len;
        }

        @Override
        public String readLine() throws IOException {
            if (len <= 0) {
                return null;
            }

            String line = super.readLine();
            if (line == null) {
                return null;
            }

            int lenght = line.getBytes(ENCODING).length;
            int toRead = (lenght <= len ? lenght : (int)len);
            len -= lenght;

            byte[] dest = new byte[toRead];
            System.arraycopy(line.getBytes(ENCODING), 0, new byte[toRead], 0, toRead);

            return new String(dest, ENCODING);
        }
    }

}
