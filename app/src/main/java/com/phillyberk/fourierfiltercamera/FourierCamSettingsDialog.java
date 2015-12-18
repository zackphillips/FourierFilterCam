/*
 * Developed as part of the Computational CellScope Project
 * Waller Lab, EECS Dept., The University of California at Berkeley
 *
 * Licensed under the 3-Clause BSD License:
 *
 * Copyright © 2015 Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the owner nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, 
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) 
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package com.phillyberk.fourierfiltercamera;

import com.phillyberk.fourierfiltercamera.R;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

public class FourierCamSettingsDialog extends DialogFragment{
	public static String TAG = "Settings Dialog";
	  
    public interface NoticeDialogListener {
        public void onDialogPositiveClick(DialogFragment dialog);
        public void onDialogNegativeClick(DialogFragment dialog);
    }
    
    // Use this instance of the interface to deliver action events
    NoticeDialogListener mListener;
    private CheckBox settingsColorFilterCheckbox;
    private CheckBox settingsIsotropicFiltersCheckbox;
    private Spinner  settingsResolutionSpinner;
    private EditText annulusWidthEditText;
    
    // Override the Fragment.onAttach() method to instantiate the NoticeDialogListener
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        
        
        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the NoticeDialogListener so we can send events to the host
            mListener = (NoticeDialogListener) activity;
        } catch (ClassCastException e) {
            // The activity doesn't implement the interface, throw exception
            throw new ClassCastException(activity.toString()
                    + " must implement NoticeDialogListener");
        }
    }
    
    public static interface OnCompleteListener {
        public abstract void onComplete(String time);
    }
    
    
	  @Override
	  public Dialog onCreateDialog(Bundle savedInstanceState) {
	      AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
	      // Get the layout inflater
	      LayoutInflater inflater = getActivity().getLayoutInflater();
	      View content = inflater.inflate(R.layout.settings_layout, null);

	      

	      // Inflate and set the layout for the dialog
	      // Pass null as the parent view because its going in the dialog layout
	      builder.setView(content);
	      // Add action buttons
          builder.setPositiveButton("Set", new DialogInterface.OnClickListener() {
             @Override
             public void onClick(DialogInterface dialog, int id) {
    	         FourierCamActivity callingActivity = (FourierCamActivity) getActivity();
                 callingActivity.setResolutionIdx(settingsResolutionSpinner.getSelectedItemPosition());
                 callingActivity.setColorFilterFlag(settingsColorFilterCheckbox.isChecked());
                 callingActivity.setIsotropicFilterFlag(settingsColorFilterCheckbox.isChecked());

             }
         })
         .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
             public void onClick(DialogInterface dialog, int id) {
            	 dialog.dismiss();
             }
         });

          FourierCamActivity callingActivity = (FourierCamActivity) getActivity();

          settingsColorFilterCheckbox = (CheckBox) content.findViewById(R.id.settingsColorFiltersCheckbox);
          settingsIsotropicFiltersCheckbox = (CheckBox) content.findViewById(R.id.settingsIsoTropicFiltersCheckbox);
          settingsResolutionSpinner = (Spinner) content.findViewById(R.id.resolutionSpinner);

          settingsColorFilterCheckbox.setChecked(callingActivity.getColorFilterFlag());
          settingsIsotropicFiltersCheckbox.setChecked(callingActivity.getIsotropicFilterFlag());

          ArrayAdapter<String> adapter = new ArrayAdapter<String>(this.getActivity(),
                  android.R.layout.simple_spinner_item, callingActivity.getResolutionListString());


          //Log.i("tagtest", callingActivity.getResolutionListString()[0]);
          //Log.i("tagtest", adapter.getItem(0));

          // Hide buttons that don't do anything
          settingsIsotropicFiltersCheckbox.setVisibility(View.GONE);


          //adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
          settingsResolutionSpinner.setAdapter(adapter);
          settingsResolutionSpinner.setSelection(callingActivity.getResolutionId());

	      return builder.create();
	  }
	  
	  
	}