/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.serving.console;

import ai.djl.engine.Engine;
import ai.djl.serving.http.BadRequestException;
import ai.djl.serving.http.InternalServerException;
import ai.djl.serving.http.ResourceNotFoundException;
import ai.djl.serving.http.StatusResponse;
import ai.djl.serving.plugins.DependencyManager;
import ai.djl.serving.plugins.RequestHandler;
import ai.djl.serving.util.ConfigManager;
import ai.djl.serving.util.MutableClassLoader;
import ai.djl.serving.util.NettyUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.handler.codec.http.multipart.MixedAttribute;
import io.netty.util.internal.StringUtil;

import org.apache.commons.compress.utils.Charsets;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** A class handling inbound HTTP requests for the log API. */
public class LogRequestHandler implements RequestHandler<Void> {

    private static final Pattern PATTERN =
            Pattern.compile(
                    "^(/logs|/inferenceAddress|/upload|/dependency|/version|/config)([/?].*)?");

    /** {@inheritDoc} */
    @Override
    public boolean acceptInboundMessage(Object msg) {
        if (!(msg instanceof FullHttpRequest)) {
            return false;
        }

        FullHttpRequest req = (FullHttpRequest) msg;
        return PATTERN.matcher(req.uri()).matches();
    }

    /** {@inheritDoc} */
    @Override
    public Void handleRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            QueryStringDecoder decoder,
            String[] segments) {
        /*
         * if (!HttpMethod.GET.equals(req.method())) { throw new
         * MethodNotAllowedException(); }
         */
        String modelServerHome = ConfigManager.getModelServerHome();
        Path dir = Paths.get(modelServerHome, "logs");
        if (segments.length < 3) {
            String path = segments[1];
            if ("logs".equals(path)) {
                listLogs(ctx, dir);
            } else if ("inferenceAddress".equals(path)) {
                getInferenceAddress(ctx);
            } else if ("upload".equals(path)) {
                upload(ctx, req);
            } else if ("version".equals(path)) {
                getVersion(ctx);
            } else if ("config".equals(path)) {
                if (HttpMethod.GET.equals(req.method())) {
                    getConfig(ctx);
                } else if (HttpMethod.POST.equals(req.method())) {
                    modifyConfig(ctx, req);
                }
            } else if ("dependency".equals(path)) {
                if (HttpMethod.GET.equals(req.method())) {
                    listDependency(ctx);
                } else if (HttpMethod.POST.equals(req.method())) {
                    addDependency(ctx, req);
                }
            }
        } else if (segments.length <= 4) {
            String fileName = segments[2];
            if (segments.length == 4 && "download".equals(segments[2])) {
                fileName = segments[3];
                downloadLog(ctx, dir, fileName);
            } else if (HttpMethod.DELETE.equals(req.method()) && "dependency".equals(segments[1])) {
                deleteDependency(ctx, segments[2]);
            } else {
                int lines = NettyUtils.getIntParameter(decoder, "lines", 200);
                showLog(ctx, dir, fileName, lines);
            }
        } else {
            throw new ResourceNotFoundException();
        }
        return null;
    }

    private void modifyConfig(ChannelHandlerContext ctx, FullHttpRequest req) {

        String jsonStr = req.content().toString(Charsets.toCharset("UTF-8"));
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
        String prop = json.get("prop").getAsString();
        ConfigManager configManager = ConfigManager.getInstance();
        String configFile = configManager.getProperty("configFile", "");
        if (!"".equals(configFile)) {
            Path path = Paths.get(configFile);
            try {
                Files.writeString(path, prop, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new InternalServerException("Failed to write configuration file", e);
            }
        }
        NettyUtils.sendJsonResponse(
                ctx, new StatusResponse("Configuration modification succeeded"));
    }

    private void getConfig(ChannelHandlerContext ctx) {
        ConfigManager configManager = ConfigManager.getInstance();
        String configFile = configManager.getProperty("configFile", "");
        if (!"".equals(configFile)) {
            Path path = Paths.get(configFile);
            try {
                String config = Files.readString(path);
                NettyUtils.sendJsonResponse(ctx, new StatusResponse(config));
            } catch (IOException e) {
                throw new InternalServerException("Failed to read configuration file", e);
            }
        } else {
            throw new BadRequestException("Configuration file not found");
        }
    }

    private void getVersion(ChannelHandlerContext ctx) {
        String version = Engine.class.getPackage().getSpecificationVersion();
        NettyUtils.sendJsonResponse(ctx, new StatusResponse(version));
    }

    private void deleteDependency(ChannelHandlerContext ctx, String name) {
        String serverHome = ConfigManager.getModelServerHome();
        Path path = Paths.get(serverHome, "deps", name);
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new InternalServerException("Failed to delete " + name, e);
        }
        String msg = "Dependency deleted  successfully";
        NettyUtils.sendJsonResponse(ctx, new StatusResponse(msg));
    }

    private void listDependency(ChannelHandlerContext ctx) {
        String serverHome = ConfigManager.getModelServerHome();
        Path depDir = Paths.get(serverHome, "deps");
        List<Map<String, String>> list = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(depDir)) {
            stream.forEach(
                    f -> {
                        File file = f.toFile();
                        String fileName = file.getName();
                        if (fileName.endsWith(".jar")) {
                            Map<String, String> m = new ConcurrentHashMap<>(4);
                            m.put("name", fileName);
                            String[] arr = fileName.split("_");
                            if (arr.length == 3) {
                                m.put("groupId", arr[0]);
                                m.put("artifactId", arr[1]);
                                m.put("version", arr[2].replace(".jar", ""));
                            }
                            list.add(m);
                        }
                    });
        } catch (IOException e) {
            throw new InternalServerException("Failed to list dependency files", e);
        }
        NettyUtils.sendJsonResponse(ctx, list);
    }

    private void addDependency(ChannelHandlerContext ctx, FullHttpRequest req) {
        HttpDataFactory factory = new DefaultHttpDataFactory();
        HttpPostRequestDecoder form = new HttpPostRequestDecoder(factory, req);
        DependencyManager dm = DependencyManager.getInstance();
        try {
            List<FileUpload> fileList = new ArrayList<>();
            Map<String, String> params = new ConcurrentHashMap<>();
            List<InterfaceHttpData> bodyHttpDatas = form.getBodyHttpDatas();
            for (InterfaceHttpData data : bodyHttpDatas) {
                if (data.getHttpDataType() == HttpDataType.Attribute) {
                    MixedAttribute m = (MixedAttribute) data;
                    params.put(data.getName(), m.getValue());
                } else if (data.getHttpDataType() == HttpDataType.FileUpload) {
                    fileList.add((FileUpload) data);
                }
            }
            String type = params.getOrDefault("type", "");
            if ("engine".equals(type)) {
                String engine = params.getOrDefault("engine", "");
                dm.installEngine(engine);
            } else {
                String from = params.getOrDefault("from", "");
                if ("maven".equals(from)) {
                    String groupId = params.getOrDefault("groupId", "");
                    String artifactId = params.getOrDefault("artifactId", "");
                    String version = params.getOrDefault("version", "");
                    String dependency = groupId + ":" + artifactId + ":" + version;
                    dm.installDependency(dependency);
                } else {
                    String serverHome = ConfigManager.getModelServerHome();
                    Path depDir = Paths.get(serverHome, "deps");
                    for (FileUpload file : fileList) {
                        byte[] bytes = file.get();
                        String filename = file.getFilename();
                        Path write =
                                Files.write(
                                        Paths.get(depDir.toString(), filename),
                                        bytes,
                                        StandardOpenOption.CREATE);
                        MutableClassLoader mcl = MutableClassLoader.getInstance();
                        mcl.addURL(write.toUri().toURL());
                    }
                }
            }
            String msg = "Dependency added successfully";
            NettyUtils.sendJsonResponse(ctx, new StatusResponse(msg));
        } catch (IOException e) {
            throw new InternalServerException("Failed to install dependency", e);
        } finally {
            form.cleanFiles();
            form.destroy();
        }
    }

    private void getInferenceAddress(ChannelHandlerContext ctx) {
        ConfigManager configManager = ConfigManager.getInstance();
        String inferenceAddress =
                configManager.getProperty("inference_address", "http://127.0.0.1:8080");
        String origin = configManager.getProperty("cors_allowed_origin", "");
        String methods = configManager.getProperty("cors_allowed_methods", "");
        String headers = configManager.getProperty("cors_allowed_headers", "");
        Map<String, String> map = new ConcurrentHashMap<>(2);
        map.put("inferenceAddress", inferenceAddress);
        map.put("corsAllowed", "0");
        if (!StringUtil.isNullOrEmpty(origin)
                && !StringUtil.isNullOrEmpty(headers)
                && (!StringUtil.isNullOrEmpty(methods))) {
            if ("*".equals(methods) || methods.toUpperCase().contains("POST")) {
                map.put("corsAllowed", "1");
            }
        }
        NettyUtils.sendJsonResponse(ctx, map);
    }

    private void upload(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpPostRequestDecoder.isMultipart(req)) {
            // int sizeLimit = ConfigManager.getInstance().getMaxRequestSize();
            HttpDataFactory factory = new DefaultHttpDataFactory();
            HttpPostRequestDecoder form = new HttpPostRequestDecoder(factory, req);
            try {
                String modelServerHome = ConfigManager.getModelServerHome();
                Path dir = Paths.get(modelServerHome, "upload");
                if (!Files.isDirectory(dir)) {
                    Files.createDirectory(dir);
                }
                List<InterfaceHttpData> bodyHttpDatas = form.getBodyHttpDatas();
                InterfaceHttpData data = bodyHttpDatas.get(0);
                FileUpload fileUpload = (FileUpload) data;
                byte[] bytes = fileUpload.get();
                String filename = fileUpload.getFilename();
                Path write =
                        Files.write(
                                Paths.get(dir.toString(), filename),
                                bytes,
                                StandardOpenOption.CREATE);

                NettyUtils.sendJsonResponse(ctx, write.toUri().toString());

            } catch (IOException e) {
                throw new InternalServerException("Failed to upload file", e);
            } finally {
                form.cleanFiles();
                form.destroy();
            }
        }
    }

    private void downloadLog(ChannelHandlerContext ctx, Path dir, String fileName) {
        if (fileName.contains("..")) {
            throw new BadRequestException("Invalid log file name:" + fileName);
        }
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new BadRequestException("File does not exist");
        }
        NettyUtils.sendFile(ctx, file, true);
    }

    private void listLogs(ChannelHandlerContext ctx, Path dir) {
        if (!Files.isDirectory(dir)) {
            NettyUtils.sendJsonResponse(ctx, Collections.emptyList());
            return;
        }

        List<Map<String, String>> list = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.forEach(
                    f -> {
                        File file = f.toFile();
                        String fileName = file.getName();
                        if (fileName.endsWith(".log")) {
                            Map<String, String> m = new ConcurrentHashMap<>(3);
                            m.put("name", fileName);
                            m.put("lastModified", String.valueOf(file.lastModified()));
                            m.put("length", String.valueOf(file.length()));
                            list.add(m);
                        }
                    });
        } catch (IOException e) {
            throw new InternalServerException("Failed to list log files", e);
        }
        NettyUtils.sendJsonResponse(ctx, list);
    }

    private void showLog(ChannelHandlerContext ctx, Path dir, String fileName, int lines) {
        if (fileName.contains("..")) {
            throw new BadRequestException("Invalid log file name:" + fileName);
        }
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new BadRequestException("File does not exist");
        }

        String lastLineText = getLastLineText(file.toFile(), lines);
        NettyUtils.sendJsonResponse(ctx, lastLineText);
    }

    private String getLastLineText(File file, int lines) {
        long fileLength = file.length() - 1;
        if (fileLength < 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int readLines = 0;
            raf.seek(fileLength);
            for (long pointer = fileLength; pointer >= 0; pointer--) {
                raf.seek(pointer);
                char c;
                c = (char) raf.read();
                if (c == '\n') {
                    readLines++;
                    if (readLines == lines) {
                        break;
                    }
                }
                builder.append(c);
                fileLength = fileLength - pointer;
            }
            builder.reverse();
        } catch (IOException e) {
            throw new InternalServerException("Failed to read log file.", e);
        }
        return builder.toString();
    }
}
