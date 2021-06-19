/*
 * Copyright (c) 2007-2018 Stephen Connolly, Michael Barrientos, Michael Barrientos, Shenyu Zheng and Jenkins contributors
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
package io.jenkins.plugins.coverage.source;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.coverage.BuildUtils;
import io.jenkins.plugins.coverage.exception.CoverageException;
import io.jenkins.plugins.coverage.targets.CoveragePaint;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefaultSourceFileResolver extends SourceFileResolver {

    public static final String DEFAULT_SOURCE_CODE_STORE_DIRECTORY = "coverage-sources/";

    @DataBoundConstructor
    public DefaultSourceFileResolver(SourceFileResolverLevel level) {
        super(level);
    }

    public void resolveSourceFiles(Run<?, ?> run, FilePath workspace, TaskListener listener, Map<String, CoveragePaint> paints) throws IOException {
        if (getLevel() == null || getLevel().equals(SourceFileResolverLevel.NEVER_STORE)) {
            return;
        }
        File runRootDir = run.getRootDir();

        listener.getLogger().printf("Source File Navigation is enabled - Current level: %s%n", getLevel());

        if (getLevel().equals(SourceFileResolverLevel.STORE_LAST_BUILD)) {
            Run<?, ?> lastBuild = BuildUtils.getPreviousNotFailedCompletedBuild(run);

            // only store source files in this build and the last not failed completed build.
            if (lastBuild != null) {
                Run<?, ?> b = BuildUtils.getPreviousNotFailedCompletedBuild(lastBuild);
                if (b != null) {
                    File sourceFileDir = new File(b.getRootDir(), "coverage-sources");

                    if (sourceFileDir.exists() && sourceFileDir.isDirectory()) {
                        FileUtils.deleteDirectory(sourceFileDir);
                    }
                }
            }
        }

        listener.getLogger().printf("%d source files need to be copied.%n", paints.size());

        final Map<String, FilePath> sourceFileMapping = createSourceFileMapping(workspace, listener);

        paints.forEach((sourceFilePath, paint) -> {
            final FilePath buildDirSourceFile = new FilePath(new File(runRootDir, DEFAULT_SOURCE_CODE_STORE_DIRECTORY + sanitizeFilename(sourceFilePath)));

            try {
                listener.getLogger().printf("Starting copy source file %s. %n", sourceFilePath);

                Set<String> possibleParentPaths = getPossiblePaths();
                if (possibleParentPaths == null) {
                    possibleParentPaths = Collections.emptySet();
                }

                final boolean copiedSucceed = workspace.act(new SourceFilePainter(
                        sourceFilePath,
                        paint,
                        buildDirSourceFile,
                        possibleParentPaths,
                        sourceFileMapping
                ));
                if (copiedSucceed) {
                    listener.getLogger().printf("Copied %s. %n", sourceFilePath);

                }

            } catch (IOException | InterruptedException e) {
                listener.getLogger().println(e.getMessage());
            }
        });
    }

    private String sanitizeFilename(String inputName) {
        return inputName.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }

    private Map<String, FilePath> createSourceFileMapping(FilePath workspace, TaskListener listener) {
        try {
            return Arrays
                    .stream(workspace.list("**/*"))
                    .collect(Collectors.toMap(
                            FilePath::getName,
                            Function.identity(),
                            (path1, path2) -> {
                                return path1;
                            }
                    ));
        } catch (IOException | InterruptedException e) {
            listener.getLogger().println(e);
        }

        return Collections.emptyMap();
    }

    @Symbol("sourceFiles")
    @Extension
    //FIXME - Why is this parametrized? T is never used.
    public static final class DefaultSourceFileResolverDescriptor<T extends SourceFileResolver> extends Descriptor<SourceFileResolver> {

        private static final ListBoxModel LEVELS = new ListBoxModel(
                new ListBoxModel.Option(SourceFileResolver.SourceFileResolverLevel.NEVER_STORE.getName(), SourceFileResolver.SourceFileResolverLevel.NEVER_STORE.toString()),
                new ListBoxModel.Option(SourceFileResolver.SourceFileResolverLevel.STORE_LAST_BUILD.getName(), SourceFileResolver.SourceFileResolverLevel.STORE_LAST_BUILD.toString()),
                new ListBoxModel.Option(SourceFileResolver.SourceFileResolverLevel.STORE_ALL_BUILD.getName(), SourceFileResolver.SourceFileResolverLevel.STORE_ALL_BUILD.toString()));

        public DefaultSourceFileResolverDescriptor() {
            super(DefaultSourceFileResolver.class);
        }

        //FIXME - This method is never used. Can we delete it?
        public ListBoxModel doFillLevelItems() {
            return LEVELS;
        }
    }

    private static class SourceFilePainter extends MasterToSlaveFileCallable<Boolean> {
        private static final long serialVersionUID = 6548573019315830249L;

        private final String sourceFilePath;
        private final Set<String> possiblePaths;
        private final CoveragePaint paint;
        private final FilePath destination;
        private final Map<String, FilePath> sourceFileMapping;

        SourceFilePainter(
                @Nonnull String sourceFilePath,
                @Nonnull CoveragePaint paint,
                @Nonnull FilePath destination,
                @Nonnull Set<String> possiblePaths,
                @Nonnull Map<String, FilePath> sourceFileMapping
        ) {
            this.sourceFilePath = sourceFilePath;
            this.paint = paint;
            this.destination = destination;
            this.possiblePaths = possiblePaths;
            this.sourceFileMapping = sourceFileMapping;
        }

        @Override
        public Boolean invoke(File workspace, VirtualChannel channel) throws IOException {
            FilePath sourceFile = tryFindSourceFile(workspace);
            if (sourceFile == null) {
                throw new IOException(
                        String.format("Unable to find source file %s in workspace %s", sourceFilePath, workspace.getAbsolutePath()));
            }

            try {
                paintSourceCode(sourceFile, paint, destination);
            } catch (CoverageException e) {
                throw new IOException(e);
            }

            return true;
        }

        private FilePath tryFindSourceFile(File workspace) {
            List<File> possibleDirectories = new LinkedList<>();

            // guess its parent directory
            for (String directory : possiblePaths) {
                File pathFromRoot = new File(directory);
                if (pathFromRoot.exists() && pathFromRoot.isDirectory()) {
                    possibleDirectories.add(pathFromRoot);
                }

                File pathFromWorkDir = new File(workspace, directory);
                if (pathFromWorkDir.exists() && pathFromWorkDir.isDirectory() && !pathFromWorkDir.equals(pathFromRoot)) {
                    possibleDirectories.add(pathFromWorkDir);
                }
            }

            // check if we can find source file in workspace
            File sourceFile = new File(workspace, sourceFilePath);
            if (isValidSourceFile(sourceFile)) {
                return new FilePath(sourceFile);
            }

            // check if we can find source file in the possible parent directories
            for (File directory : possibleDirectories) {
                sourceFile = new File(directory, sourceFilePath);
                if (isValidSourceFile(sourceFile)) {
                    return new FilePath(sourceFile);
                }
            }

            // if sourceFilePath is a absolute path check if it is under the workspace directory
            if (Paths.get(sourceFilePath).isAbsolute()
                    && Paths.get(sourceFilePath).normalize().startsWith(workspace.getAbsolutePath())) {
                sourceFile = new File(sourceFilePath);
                if (isValidSourceFile(sourceFile)) {
                    return new FilePath(sourceFile);
                }
            }

            // last but not least, search for the sourceFilePath within the entire workspace
            // for Cobertura, this step attempts to ignore CoverageFeatureConstants.FEATURE_SOURCE_FILE_PATH
            // or 'source-file-path':'coverage/sources/source' as set in Cobertura output XML files
            /* NOTES:
                https://issues.jenkins.io/browse/JENKINS-5235
                https://github.com/jenkinsci/cobertura-plugin/issues/103
                https://github.com/jenkinsci/cobertura-plugin/issues/61

            */
            try (Stream<Path> walk = Files.walk(Paths.get(workspace.getAbsolutePath()))) {
                List<Path> results = walk
                .filter(Files::isRegularFile)
                .filter(x -> x.endsWith(sourceFilePath))
                .collect(Collectors.toList());

                // what if two files have the same sourceFilePath, but are in different parent directories?
                if (results.size() > 0) {
                    sourceFile = new File(results.get(0).toString());
                    if (isValidSourceFile(sourceFile)) {
                        return new FilePath(sourceFile);
                    }
                }
            } catch (IOException e) { // do nothing
                //e.printStackTrace();
            }

            // fallback to use the pre-scanned workspace to see if there's a file that matches
            return sourceFileMapping.get(sourceFilePath);
        }

        private boolean isValidSourceFile(File sourceFile) {
            return sourceFile.exists() && sourceFile.isFile() && sourceFile.canRead();
        }

        private void paintSourceCode(FilePath source, CoveragePaint paint, FilePath canvas) throws CoverageException {
            try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(canvas.write(), StandardCharsets.UTF_8));
                 BufferedReader input = new BufferedReader(new InputStreamReader(source.read(), StandardCharsets.UTF_8))) {
                int line = 0;
                String content;
                while ((content = input.readLine()) != null) {
                    line++;

                    if (paint.isPainted(line)) {
                        final int hits = paint.getHits(line);
                        final int branchCoverage = paint.getBranchCoverage(line);
                        final int branchTotal = paint.getBranchTotal(line);
                        final int coveragePercent = (hits == 0) ? 0 : (int) (branchCoverage * 100.0 / branchTotal);
                        if (paint.getHits(line) > 0) {
                            if (branchTotal == branchCoverage) {
                                output.write("<tr class=\"coverFull\">\n");
                            } else {
                                output.write("<tr class=\"coverPart\" title=\"Line " + line + ": Conditional coverage " + coveragePercent + "% ("
                                        + branchCoverage + "/" + branchTotal + ")\">\n");
                            }
                        } else {
                            output.write("<tr class=\"coverNone\">\n");
                        }
                        output.write("<td class=\"line\"><a name='" + line + "'>" + line + "</a></td>\n");
                        output.write("<td class=\"hits\">" + hits + "</td>\n");
                    } else {
                        output.write("<tr class=\"noCover\">\n");
                        output.write("<td class=\"line\"><a name='" + line + "'>" + line + "</a></td>\n");
                        output.write("<td class=\"hits\"></td>\n");
                    }
                    output.write("<td class=\"code\">"
                            + content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "").replace("\r", "").replace(" ",
                            "&nbsp;").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;") + "</td>\n");
                    output.write("</tr>\n");
                }

                paint.setTotalLines(line);
            } catch (IOException | InterruptedException e) {
                throw new CoverageException(e);
            }
        }
    }
}
