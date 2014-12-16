package com.securecomcode.messaging.jobs.requirements;

import android.content.Context;
import android.os.Looper;
import android.os.MessageQueue;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.sms.TelephonyServiceState;
import org.whispersystems.jobqueue.dependencies.ContextDependent;
import org.whispersystems.jobqueue.requirements.Requirement;

public class ServiceRequirement implements Requirement, ContextDependent {

  private static final String TAG = ServiceRequirement.class.getSimpleName();

  private final transient ServiceRequirementProvider provider;

  private transient Context context;

  public ServiceRequirement(Context context) {
    this.context  = context;
    this.provider = (ServiceRequirementProvider)ApplicationContext.getInstance(context)
                                                                  .getJobManager()
                                                                  .getRequirementProvider("telephony-service");
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }

  @Override
  public boolean isPresent() {
    TelephonyServiceState telephonyServiceState = new TelephonyServiceState();
    return telephonyServiceState.isConnected(context);
  }
}
