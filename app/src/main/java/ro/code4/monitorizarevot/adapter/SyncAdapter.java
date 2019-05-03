package ro.code4.monitorizarevot.adapter;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.util.List;

import ro.code4.monitorizarevot.ToolbarActivity;
import ro.code4.monitorizarevot.constants.Sync;
import ro.code4.monitorizarevot.db.Data;
import ro.code4.monitorizarevot.net.NetworkService;
import ro.code4.monitorizarevot.net.model.*;
import ro.code4.monitorizarevot.net.model.response.VersionResponse;
import ro.code4.monitorizarevot.observable.ObservableListener;
import ro.code4.monitorizarevot.util.Logify;

import static ro.code4.monitorizarevot.util.AuthUtils.createSyncAccount;

public class SyncAdapter extends AbstractThreadedSyncAdapter {
    private static ToolbarActivity.SyncDataCallback callback;

    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        init();
    }

    public SyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        init();
    }

    public static void requestSync(Context context) {
        Account account = createSyncAccount(context);
        ContentResolver.setSyncAutomatically(account, Sync.AUTHORITY, true);
        ContentResolver.requestSync(account, Sync.AUTHORITY, getBundle(false));
    }

    public static void requestSync(Context context, ToolbarActivity.SyncDataCallback callback) {
        SyncAdapter.callback = callback;

        requestSync(context);
    }

    public static void requestUploadSync(Context context) {
        if (ContentResolver.getMasterSyncAutomatically()) {
            Account account = createSyncAccount(context);
            ContentResolver.setSyncAutomatically(account, Sync.AUTHORITY, true);
            ContentResolver.requestSync(account, Sync.AUTHORITY, getBundle(true));
        }
    }

    @NonNull
    private static Bundle getBundle(boolean isUpload) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, isUpload);
        return extras;
    }

    private void init() {

    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Logify.d("SyncAdapter", "performing sync");

        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false)) {
            doUpload();
        } else {
            doSync();
        }
    }

    private void doUpload() {
        postBranchDetails();
        postQuestionAnswers();
        postNotes();
    }

    private void doSync() {
        doUpload();
        getFormsDefinition();
    }

    private void postBranchDetails() {
        List<BranchDetails> branchDetailsList = Data.getInstance().getUnsyncedList(BranchDetails.class);
        for (BranchDetails branchDetails : branchDetailsList) {
            try {
                NetworkService.postBranchDetails(branchDetails);
                Data.getInstance().markSynced(branchDetails);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void postQuestionAnswers() {
        try {
            List<QuestionAnswer> questionAnswers = Data.getInstance().getUnsyncedQuestionAnswersFromAllForms();
            NetworkService.postQuestionAnswer(new ResponseAnswerContainer(questionAnswers));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void postNotes() {
        List<Note> notes = Data.getInstance().getNotes();
        for (Note note : notes) {
            try {
                NetworkService.postNote(note);
                Data.getInstance().deleteNote(note);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void getFormsDefinition() {
        try {
            VersionResponse versionResponse = NetworkService.doGetFormVersion();
            List<Version> existingVersions = Data.getInstance().getFormVersions();
            if (existingVersions == null || !existingVersions.equals(versionResponse.getVersions())) {
                Data.getInstance().deleteAnswersAndNotes();
                getForms(versionResponse.getVersions());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void getForms(List<Version> versions) {
        FormDefinitionSubscriber subscriber = new FormDefinitionSubscriber(versions, versions.size());

        for (Version versionEntry: versions){
            NetworkService.doGetForm(versionEntry.getKey()).startRequest(subscriber);
        }
    }

    private class FormDefinitionSubscriber extends ObservableListener<Boolean> {

        private final List<Version> versions;

        private final int numberOfRequests;

        private int successCount = 0;

        FormDefinitionSubscriber(List<Version> versions, int numberOfRequests) {
            this.versions = versions;
            this.numberOfRequests = numberOfRequests;
        }

        @Override
        public void onSuccess() {
            if (successCount == numberOfRequests) {
                Data.getInstance().saveFormsVersion(versions);

                if (callback != null) {
                    callback.onSyncedForms();
                }
            }
        }

        @Override
        public void onError(Throwable e) {

        }

        @Override
        public void onNext(Boolean aBoolean) {
            successCount++;
        }
    }
}
