package com.hover.stax.transfers;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.amplitude.api.Amplitude;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.hover.stax.R;
import com.hover.stax.actions.Action;
import com.hover.stax.database.Constants;
import com.hover.stax.hover.HoverSession;
import com.hover.stax.schedules.Schedule;
import com.hover.stax.schedules.ScheduleDetailViewModel;
import com.hover.stax.security.BiometricChecker;
import com.hover.stax.utils.StagedViewModel;
import com.hover.stax.utils.UIHelper;
import com.hover.stax.views.StaxDialog;

import static com.hover.stax.transfers.TransferStage.*;

public class TransferActivity extends AppCompatActivity implements BiometricChecker.AuthListener {
	final public static String TAG = "TransferActivity";

	private TransferViewModel transferViewModel;
	private ScheduleDetailViewModel scheduleViewModel = null;
	private  boolean isFromStaxLink = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		transferViewModel = new ViewModelProvider(this).get(TransferViewModel.class);


		startObservers();
		checkIntent();
		handleStaxLinkIntent(getIntent());
		setContentView(R.layout.activity_transfer);



	}

	private void startObservers() {
		transferViewModel.getSelectedChannels().observe(this, channels -> {
			if (scheduleViewModel != null && scheduleViewModel.getSchedule().getValue() != null)
				transferViewModel.setActiveChannel(scheduleViewModel.getSchedule().getValue().channel_id);
		});
		transferViewModel.getActiveChannel().observe(this, channel -> Log.i(TAG, "This observer is neccessary to make updates fire, but all logic is in viewmodel."));
		transferViewModel.getActions().observe(this, actions ->{
			onUpdateStage(transferViewModel.getStage().getValue());
		} );
		transferViewModel.getActiveAction().observe(this, action -> onUpdateStage(transferViewModel.getStage().getValue()));

		transferViewModel.getStage().observe(this, this::onUpdateStage);
		transferViewModel.getIsFuture().observe(this, isFuture -> onUpdateStage(transferViewModel.getStage().getValue()));
		transferViewModel.getFutureDate().observe(this, date -> onUpdateStage(transferViewModel.getStage().getValue()));
		transferViewModel.repeatSaved().observe(this, isSaved -> onUpdateStage(transferViewModel.getStage().getValue()));
		transferViewModel.getIsEditing().observe(this, isEditing -> onUpdateStage(transferViewModel.getStage().getValue()));

		transferViewModel.setType(getIntent().getAction());

		transferViewModel.getIschannelRelationshipExist().observe(this, relationshipExists->{
			if(relationshipExists !=null && !relationshipExists) {
				new StaxDialog(TransferActivity.this)
						.setDialogTitle(R.string.stax_cannot_make_transfer_title)
						.setDialogMessage(R.string.stax_cannot_make_transfer_desc)
						.setPosButton(R.string.btn_ok, null)
						.showIt();
			}
		});
		transferViewModel.getIschannelRelationshipExistMediator().observe(this, status-> { });
	}

	private void checkIntent() {
		if (getIntent().hasExtra(Schedule.SCHEDULE_ID)) {
			createFromSchedule(getIntent().getIntExtra(Schedule.SCHEDULE_ID, -1));
		} else Amplitude.getInstance().logEvent(getString(R.string.visit_screen, getIntent().getAction()));
	}

	private void createFromSchedule(int schedule_id) {
		scheduleViewModel = new ViewModelProvider(this).get(ScheduleDetailViewModel.class);
		scheduleViewModel.getAction().observe(this, action -> {
			if (action != null) transferViewModel.setActiveAction(action);
		});
		scheduleViewModel.getSchedule().observe(this, schedule -> {
			if (schedule == null) return;
			transferViewModel.view(schedule);
		});
		scheduleViewModel.setSchedule(schedule_id);
		Amplitude.getInstance().logEvent(getString(R.string.clicked_schedule_notification));
	}

	public void onContinue(View view) {
		if (transferViewModel.isDone())
			submit();
		else if (transferViewModel.stageValidates())
			transferViewModel.goToNextStage();
	}

	private void submit() {
		if (transferViewModel.getIsFuture().getValue() != null && transferViewModel.getIsFuture().getValue() && transferViewModel.getFutureDate().getValue() != null) {
			transferViewModel.schedule();
			returnResult(Constants.SCHEDULE_REQUEST, RESULT_OK);
		} else {
			if (transferViewModel.repeatSaved().getValue() != null && transferViewModel.repeatSaved().getValue())
				transferViewModel.schedule();
			authenticate();
		}
	}

	private void authenticate() {
//		if (transferViewModel.getActiveAction().getValue() == null)

		new BiometricChecker(this, this).startAuthentication(transferViewModel.getActiveAction().getValue());
//		makeHoverCall(transferViewModel.getActiveAction().getValue());
	}

	@Override
	public void onAuthError(String error) {
		Log.e(TAG, error);
	}

	@Override
	public void onAuthSuccess(Action act) {
		makeHoverCall(act);
	}

	private void makeHoverCall(Action act) {
		Amplitude.getInstance().logEvent(getString(R.string.finish_transfer, transferViewModel.getType()));
		transferViewModel.checkSchedule();
		new HoverSession.Builder(act, transferViewModel.getActiveChannel().getValue(),
				TransferActivity.this, Constants.TRANSFER_REQUEST)
				.extra(Action.PHONE_KEY, transferViewModel.getRecipient().getValue())
				.extra(Action.ACCOUNT_KEY, transferViewModel.getRecipient().getValue())
				.extra(Action.AMOUNT_KEY, transferViewModel.getAmount().getValue())
				.extra(Action.REASON_KEY, transferViewModel.getNote().getValue())
				.run();
	}

	private void onUpdateStage(@Nullable StagedViewModel.StagedEnum stage) {
		if (Navigation.findNavController(this, R.id.nav_host_fragment).getCurrentDestination().getId() == R.id.navigation_edit)
			((ExtendedFloatingActionButton) findViewById(R.id.fab)).hide();
		else if (findViewById(R.id.amountRow) != null) {
			setSummaryCard(stage);
			setCurrentCard(stage);
			setFab(stage);
		}
	}

	private void setSummaryCard(@Nullable StagedViewModel.StagedEnum stage) {
		if(isFromStaxLink) {
			findViewById(R.id.amountRow).setVisibility(View.VISIBLE);
			findViewById(R.id.actionRow).setVisibility(View.VISIBLE);
			findViewById(R.id.recipientRow).setVisibility(View.VISIBLE);
		}
		else {
			findViewById(R.id.amountRow).setVisibility(stage.compare(AMOUNT) > 0 ? View.VISIBLE : View.GONE);
			findViewById(R.id.actionRow).setVisibility(stage.compare(TO_NETWORK) > 0 &&
															   transferViewModel.getActions().getValue() != null && transferViewModel.getActions().getValue().size() > 0 && (transferViewModel.getActions().getValue().size() > 1 || transferViewModel.getActiveAction().getValue().hasToInstitution()) ? View.VISIBLE : View.GONE);
			findViewById(R.id.recipientRow).setVisibility(stage.compare(RECIPIENT) > 0 && transferViewModel.getActiveAction().getValue() != null ? View.VISIBLE : View.GONE);
			findViewById(R.id.btnRow).setVisibility(stage.compare(AMOUNT) > 0 ? View.VISIBLE : View.GONE);
		}
		findViewById(R.id.accountRow).setVisibility(stage.compare(FROM_ACCOUNT) > 0 ? View.VISIBLE : View.GONE);
		findViewById(R.id.noteRow).setVisibility((stage.compare(NOTE) > 0 && transferViewModel.getNote().getValue() != null && !transferViewModel.getNote().getValue().isEmpty()) ? View.VISIBLE : View.GONE);

	}

	private void setCurrentCard(StagedViewModel.StagedEnum stage) {
//		findViewById(R.id.summaryCard).setVisibility(stage.compare(AMOUNT) > 0 ? View.VISIBLE : View.GONE);
		findViewById(R.id.amountCard).setVisibility(stage.compare(AMOUNT) == 0 ? View.VISIBLE : View.GONE);
		findViewById(R.id.fromAccountCard).setVisibility(stage.compare(FROM_ACCOUNT) == 0 ? View.VISIBLE : View.GONE);
		findViewById(R.id.networkCard).setVisibility(stage.compare(TO_NETWORK) == 0 ? View.VISIBLE : View.GONE);

		if(isFromStaxLink && stage.compare(RECIPIENT) == 0) transferViewModel.goToNextStage();
		else
			findViewById(R.id.recipientCard).setVisibility(stage.compare(RECIPIENT) == 0 ? View.VISIBLE : View.GONE);


		findViewById(R.id.reasonCard).setVisibility(stage.compare(NOTE) == 0 ? View.VISIBLE : View.GONE);
		findViewById(R.id.futureCard).setVisibility(stage.compare(REVIEW_DIRECT) < 0 && transferViewModel.getFutureDate().getValue() == null ? View.VISIBLE : View.GONE);
		findViewById(R.id.repeatCard).setVisibility(stage.compare(REVIEW_DIRECT) < 0 && (transferViewModel.repeatSaved().getValue() == null || !transferViewModel.repeatSaved().getValue()) ? View.VISIBLE : View.GONE);
	}

	private void setFab(StagedViewModel.StagedEnum stage) {
		ExtendedFloatingActionButton fab = findViewById(R.id.fab);
		if (stage.compare(REVIEW) >= 0) {
			if (transferViewModel.getIsFuture().getValue() != null && transferViewModel.getIsFuture().getValue()) {
				fab.setText(getString(R.string.fab_schedule));
				if (transferViewModel.getFutureDate().getValue() == null) { fab.hide(); } else { fab.show(); }
			} else {
				fab.setText(getString(R.string.fab_transfernow));
				fab.show();
			}
		} else {
			fab.setText(R.string.btn_continue);
			fab.show();
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == Constants.TRANSFER_REQUEST) {
			returnResult(requestCode, resultCode);
		}
	}

	private void returnResult(int type, int result) {
		Intent i = new Intent();
		if (type == Constants.SCHEDULE_REQUEST) {
			i.putExtra(Schedule.DATE_KEY, transferViewModel.getFutureDate().getValue());
		}
		i.setAction(type == Constants.SCHEDULE_REQUEST ? Constants.SCHEDULED : Constants.TRANSFERED);
		setResult(result, i);
		finish();
	}

	private void handleStaxLinkIntent(Intent intent) {
		if(intent.getExtras()!=null && intent.hasExtra(Constants.SOCIAL_LINK)) {
			isFromStaxLink = true;
			String encryptedString = intent.getExtras().getString(Constants.SOCIAL_LINK);
			transferViewModel.setupTransferPageFromPaymentLink(encryptedString);
		}
	}

	@Override
	public void onBackPressed() {
		if (Navigation.findNavController(findViewById(R.id.nav_host_fragment)).getCurrentDestination().getId() != R.id.navigation_edit ||
			    !Navigation.findNavController(findViewById(R.id.nav_host_fragment)).popBackStack())
			super.onBackPressed();
	}
}