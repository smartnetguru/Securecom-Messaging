package com.securecomcode.messaging;

import android.os.Bundle;

import org.whispersystems.textsecure.crypto.MasterSecret;

import com.actionbarsherlock.app.SherlockListActivity;

public class PassphraseRequiredSherlockListActivity extends SherlockListActivity implements PassphraseRequiredActivity {

  private final PassphraseRequiredMixin delegate = new PassphraseRequiredMixin();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    delegate.onCreate(this);
  }

  @Override
  protected void onResume() {
    super.onResume();
    delegate.onResume(this);
  }

  @Override
  protected void onPause() {
    super.onPause();
    delegate.onPause(this);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    delegate.onDestroy(this);
  }

  @Override
  public void onMasterSecretCleared() {
    finish();
  }

  @Override
  public void onNewMasterSecret(MasterSecret masterSecret) {}

}
