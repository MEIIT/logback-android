/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.core.rolling.helper;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.util.FileSize;

public class TimeBasedArchiveRemover extends ContextAwareBase implements ArchiveRemover {

  protected final FileNamePattern fileNamePattern;
  private final RollingCalendar rc;
  private int maxHistory = CoreConstants.UNBOUND_HISTORY;
  private long totalSizeCap = CoreConstants.UNBOUNDED_TOTAL_SIZE_CAP;
  private final FileProvider fileProvider;
  private final FileSorter fileSorter;
  private final DateParser dateParser;

  public TimeBasedArchiveRemover(FileNamePattern fileNamePattern, RollingCalendar rc, FileProvider fileProvider) {
    this.fileNamePattern = fileNamePattern;
    this.rc = rc;
    this.fileProvider = fileProvider;
    this.dateParser = new DateParser(fileNamePattern);
    this.fileSorter = new FileSorter(dateParser, new IntParser(fileNamePattern));
  }

  public void clean(final Date now) {
    String[] files = this.findFiles();
    String[] expiredFiles = this.filterFiles(files, this.createExpiredFileFilter(now));
    for (String f : expiredFiles) {
      this.delete(new File(f));
    }

    if (this.totalSizeCap != CoreConstants.UNBOUNDED_TOTAL_SIZE_CAP && this.totalSizeCap > 0) {
      String[] recentFiles = this.filterFiles(files, this.createRecentFileFilter(now));
      this.capTotalSize(recentFiles);
    }

    String[] emptyDirs = this.findEmptyDirs();
    for (String dir : emptyDirs) {
      this.delete(new File(dir));
    }
  }

  private boolean delete(File file) {
    addInfo("deleting " + file);
    boolean ok = this.fileProvider.deleteFile(file);
    if (!ok) {
      addWarn("cannot delete " + file);
    }
    return ok;
  }

  private void capTotalSize(String[] filenames) {
    long totalSize = 0;
    long totalRemoved = 0;

    this.fileSorter.sort(filenames);
    for (String name : filenames) {
      File f = new File(name);
      long size = this.fileProvider.length(f);
      if (totalSize + size > this.totalSizeCap) {
        addInfo("Deleting [" + f + "]" + " of size " + new FileSize(size));
        if (!delete(f)) {
          size = 0;
        }
        totalRemoved += size;
      }
      totalSize += size;
    }

    addInfo("Removed  "+ new FileSize(totalRemoved) + " of files");
  }

  public void setMaxHistory(int maxHistory) {
    this.maxHistory = maxHistory;
  }

  public void setTotalSizeCap(long totalSizeCap) {
    this.totalSizeCap = totalSizeCap;
  }

  public String toString() {
    return "c.q.l.core.rolling.helper.TimeBasedArchiveRemover";
  }

  public Future<?> cleanAsynchronously(Date now) {
    ArchiveRemoverRunnable runnable = new ArchiveRemoverRunnable(now);
    ExecutorService executorService = context.getScheduledExecutorService();
    return executorService.submit(runnable);
  }

  private FilenameFilter createRecentFileFilter(Date baseDate) {
    return createFileFilter(baseDate, false);
  }

  private FilenameFilter createExpiredFileFilter(Date baseDate) {
    return createFileFilter(baseDate, true);
  }

  private FilenameFilter createFileFilter(Date baseDate, boolean before) {
    return (File unused, String path) -> {
      Date fileDate = this.dateParser.parseFilename(path);
      Date adjustedDate = rc.getEndOfNextNthPeriod(baseDate, -maxHistory);
      int comparison = fileDate.compareTo(adjustedDate);
      return before ? (comparison < 0) : (comparison >= 0);
    };
  }

  private String[] filterFiles(String[] filenames, FilenameFilter filter) {
    ArrayList<String> matchedFiles = new ArrayList<>();
    for (String f : filenames) {
      if (filter.accept(null, f)) {
        matchedFiles.add(f);
      }
    }
    return matchedFiles.toArray(new String[0]);
  }

  private String[] findFiles() {
    return new FileFinder(this.fileProvider).findFiles(this.fileNamePattern.toRegex());
  }

  private String[] findEmptyDirs() {
    String[] dirs = new FileFinder(this.fileProvider).findDirs(this.fileNamePattern.toRegex());

    // Assuming directories were already sorted, let's reverse it
    // so we can iterate the list deepest first (deletes deepest
    // dirs first so their parents would be empty for deletion
    // to succeed).
    List<String> dirList = Arrays.asList(dirs);
    Collections.reverse(dirList);
    ArrayDeque<String> emptyDirs = new ArrayDeque<>();
    for (String dir : dirList) {
      int childSize = this.fileProvider.list(new File(dir), null).length;
      if (childSize == 0 || (childSize == 1 && emptyDirs.size() > 0 && dir.equals(emptyDirs.peekLast()))) {
        emptyDirs.add(dir);
      }
    }
    return emptyDirs.toArray(new String[0]);
  }

  private class ArchiveRemoverRunnable implements Runnable {
    Date now;
    ArchiveRemoverRunnable(Date now) {
      this.now = now;
    }

    @Override
    public void run() {
      clean(now);
    }
  }
}
