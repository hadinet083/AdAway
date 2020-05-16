package org.adaway.model.root;

import android.content.Context;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.view.HostEntry;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.error.HostErrorException;
import org.adaway.util.AppExecutors;
import org.adaway.util.Log;
import org.adaway.util.ShellUtils;
import org.adaway.util.WebServerUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import static android.content.Context.MODE_PRIVATE;
import static org.adaway.db.entity.ListType.REDIRECTED;
import static org.adaway.model.adblocking.AdBlockMethod.ROOT;
import static org.adaway.model.error.HostError.COPY_FAIL;
import static org.adaway.model.error.HostError.NOT_ENOUGH_SPACE;
import static org.adaway.model.error.HostError.PRIVATE_FILE_FAILED;
import static org.adaway.model.error.HostError.REVERT_FAIL;
import static org.adaway.util.Constants.ANDROID_SYSTEM_ETC_HOSTS;
import static org.adaway.util.Constants.COMMAND_CHMOD_644;
import static org.adaway.util.Constants.COMMAND_CHOWN;
import static org.adaway.util.Constants.DEFAULT_HOSTS_FILENAME;
import static org.adaway.util.Constants.HOSTS_FILENAME;
import static org.adaway.util.Constants.LINE_SEPARATOR;
import static org.adaway.util.Constants.LOCALHOST_HOSTNAME;
import static org.adaway.util.Constants.LOCALHOST_IPv4;
import static org.adaway.util.Constants.LOCALHOST_IPv6;
import static org.adaway.util.Constants.TAG;
import static org.adaway.util.MountType.READ_ONLY;
import static org.adaway.util.MountType.READ_WRITE;
import static org.adaway.util.ShellUtils.mergeAllLines;

/**
 * This class is the model to represent hosts file installation.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class RootModel extends AdBlockModel {
    private static final String HEADER1 = "# This hosts file has been generated by AdAway on: ";
    private static final String HEADER2 = "# Please do not modify it directly, it will be overwritten when AdAway is applied again.";
    private static final String HEADER_SOURCES = "# This file is generated from the following sources:";
    private final HostsSourceDao hostsSourceDao;
    private final HostEntryDao hostEntryDao;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public RootModel(Context context) {
        super(context);
        // Get DOA
        AppDatabase database = AppDatabase.getInstance(this.context);
        this.hostsSourceDao = database.hostsSourceDao();
        this.hostEntryDao = database.hostEntryDao();
        // Check if host list is applied
        Executor executor = AppExecutors.getInstance().diskIO();
        executor.execute(this::checkApplied);
        executor.execute(() -> syncPreferences(context));
    }

    @Override
    public AdBlockMethod getMethod() {
        return ROOT;
    }

    @Override
    public void apply() throws HostErrorException {
        setState(R.string.status_apply_sources);
        setState(R.string.status_create_new_hosts);
        createNewHostsFile();
        setState(R.string.status_copy_new_hosts);
        copyNewHostsFile();
        setState(R.string.status_check_copy);
        setState(R.string.status_hosts_updated);
        this.applied.postValue(true);
    }

    /**
     * Revert to the default hosts file.
     *
     * @throws HostErrorException If the hosts file could not be reverted.
     */
    @Override
    public void revert() throws HostErrorException {
        // Update status
        setState(R.string.status_revert);
        try {
            // Revert hosts file
            revertHostFile();
            setState(R.string.status_revert_done);
            this.applied.postValue(false);
        } catch (IOException exception) {
            throw new HostErrorException(REVERT_FAIL, exception);
        }
    }

    @Override
    public boolean isRecordingLogs() {
        return TcpdumpUtils.isTcpdumpRunning();
    }

    @Override
    public void setRecordingLogs(boolean recording) {
        if (recording) {
            TcpdumpUtils.startTcpdump(this.context);
        } else {
            TcpdumpUtils.stopTcpdump();
        }
    }

    @Override
    public List<String> getLogs() {
        return TcpdumpUtils.getLogs(this.context);
    }

    @Override
    public void clearLogs() {
        TcpdumpUtils.clearLogFile(this.context);
    }

    private void checkApplied() {
        boolean applied;

        /* Check if first line in hosts file is AdAway comment */
        SuFile file = new SuFile(ANDROID_SYSTEM_ETC_HOSTS);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String firstLine = reader.readLine();

            Log.d(TAG, "First line of " + file.getName() + ": " + firstLine);

            applied = firstLine.startsWith(HEADER1);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException", e);
            applied = true; // workaround for: http://code.google.com/p/ad-away/issues/detail?id=137
        } catch (Exception e) {
            Log.e(TAG, "Exception: ", e);
            applied = false;
        }

        this.applied.postValue(applied);
    }

    private void syncPreferences(Context context) {
        if (PreferenceHelper.getWebServerEnabled(context) && !WebServerUtils.isWebServerRunning()) {
            WebServerUtils.startWebServer(context);
        }
    }

    private void deleteNewHostsFile() {
        // delete generated hosts file from private storage
        this.context.deleteFile(HOSTS_FILENAME);
    }

    private void copyNewHostsFile() throws HostErrorException {
        try {
            copyHostsFile(HOSTS_FILENAME);
        } catch (CommandException exception) {
            throw new HostErrorException(COPY_FAIL, exception);
        }
    }

    /**
     * Create a new hosts files in a private file from downloaded hosts sources.
     *
     * @throws HostErrorException If the new hosts file could not be created.
     */
    private void createNewHostsFile() throws HostErrorException {
        deleteNewHostsFile();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(this.context.openFileOutput(HOSTS_FILENAME, MODE_PRIVATE)))) {
            writeHostsHeader(writer);
            writeLoopbackToHosts(writer);
            writeHosts(writer);
        } catch (IOException exception) {
            throw new HostErrorException(PRIVATE_FILE_FAILED, exception);
        }
    }

    private void writeHostsHeader(BufferedWriter writer) throws IOException {
        // Format current date
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        Date now = new Date();
        String date = formatter.format(now);
        // Write header
        writer.write(HEADER1);
        writer.write(date);
        writer.newLine();
        writer.write(HEADER2);
        writer.newLine();
        // Write hosts source
        writer.write(HEADER_SOURCES);
        writer.newLine();
        for (HostsSource hostsSource : this.hostsSourceDao.getEnabled()) {
            writer.write("# - " + hostsSource.getUrl() + LINE_SEPARATOR);
            writer.newLine();
        }
        // Write empty line separator
        writer.newLine();
    }

    private void writeLoopbackToHosts(BufferedWriter writer) throws IOException {
        writer.write(LOCALHOST_IPv4 + " " + LOCALHOST_HOSTNAME);
        writer.newLine();
        writer.write(LOCALHOST_IPv6 + " " + LOCALHOST_HOSTNAME);
        writer.newLine();
    }

    private void writeHosts(BufferedWriter writer) throws IOException {
        // Get user preferences
        String redirectionIpv4 = PreferenceHelper.getRedirectionIpv4(this.context);
        String redirectionIpv6 = PreferenceHelper.getRedirectionIpv6(this.context);
        boolean enableIpv6 = PreferenceHelper.getEnableIpv6(this.context);
        // Write each hostname
        for (HostEntry entry: this.hostEntryDao.getAll()) {
            String hostname = entry.getHost();
            if (entry.getType() == REDIRECTED) {
                writer.write(entry.getRedirection() + " " + hostname);
                writer.newLine();
            } else {
                writer.write(redirectionIpv4 + " " + hostname);
                writer.newLine();
                if (enableIpv6) {
                    writer.write(redirectionIpv6 + " " + hostname);
                    writer.newLine();
                }
            }
        }
    }

    /**
     * Revert to default hosts file.
     *
     * @throws IOException If the hosts file could not be reverted.
     */
    private void revertHostFile() throws IOException {
        // Create private file
        try (FileOutputStream fos = this.context.openFileOutput(DEFAULT_HOSTS_FILENAME, MODE_PRIVATE)) {
            // Write default localhost as hosts file
            String localhost = LOCALHOST_IPv4 + " " + LOCALHOST_HOSTNAME + LINE_SEPARATOR +
                    LOCALHOST_IPv6 + " " + LOCALHOST_HOSTNAME + LINE_SEPARATOR;
            fos.write(localhost.getBytes());
            // Copy generated hosts file to target location
            copyHostsFile(DEFAULT_HOSTS_FILENAME);
            // Delete generated hosts file after applying it
            this.context.deleteFile(DEFAULT_HOSTS_FILENAME);
        } catch (Exception exception) {
            throw new IOException("Unable to revert hosts file.", exception);
        }
    }

    /**
     * Copy source file from private storage of AdAway to hosts file target using root commands.
     */
    private void copyHostsFile(String source) throws HostErrorException, CommandException {
        String privateDir = this.context.getFilesDir().getAbsolutePath();
        String privateFile = privateDir + File.separator + source;

        // if the target has a trailing slash, it is not a valid target!
        String target = ANDROID_SYSTEM_ETC_HOSTS;
        SuFile targetFile = new SuFile(target);

        /* check for space on partition */
        long size = new File(privateFile).length();
        Log.i(TAG, "Size of hosts file: " + size);
        if (!hasEnoughSpaceOnPartition(targetFile, size)) {
            throw new HostErrorException(NOT_ENOUGH_SPACE);
        }

        /* Execute commands */
        boolean writable = isWritable(targetFile);
        try {
            if (!writable) {
                // remount for write access
                Log.i(TAG, "Remounting for RW...");
                if (!ShellUtils.remountPartition(targetFile, READ_WRITE)) {
                    throw new CommandException("Failed to remount hosts file partition as read-write.");
                }
            }
            // Copy hosts file then set owner and permissions
            Shell.Result result = Shell.su(
                    "dd if=" + privateFile + " of=" + target,
                    COMMAND_CHOWN + " " + target,
                    COMMAND_CHMOD_644 + " " + target
            ).exec();
            if (!result.isSuccess()) {
                throw new CommandException("Failed to copy hosts file: " + mergeAllLines(result.getErr()));
            }
        } finally {
            if (!writable) {
                // after all remount target back as read only
                ShellUtils.remountPartition(targetFile, READ_ONLY);
            }
        }
    }

    /**
     * Check if there is enough space on partition where target is located
     *
     * @param size   size of file to put on partition
     * @param target path where to put the file
     * @return true if it will fit on partition of target, false if it will not fit.
     */
    private boolean hasEnoughSpaceOnPartition(SuFile target, long size) {
        long freeSpace = target.getFreeSpace();
        return (freeSpace == 0 || freeSpace > size);
    }

    /**
     * Check if a path is writable.
     *
     * @param file The file to check.
     * @return <code>true</code> if the path is writable, <code>false</code> otherwise.
     */
    private boolean isWritable(SuFile file) {
        return file.canWrite();
    }
}
