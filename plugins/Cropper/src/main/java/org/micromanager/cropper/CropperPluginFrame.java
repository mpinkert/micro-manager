///////////////////////////////////////////////////////////////////////////////
//FILE:          CropperPluginFrame.java
//PROJECT:       Micro-Manager 
//SUBSYSTEM:     Cropper plugin
//-----------------------------------------------------------------------------
//
// AUTHOR:       Nico Stuurman
//
// COPYRIGHT:    Regents of the University of California 2016
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.cropper;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.DefaultFormatter;
import net.miginfocom.swing.MigLayout;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Coords.CoordsBuilder;
import org.micromanager.data.Datastore;
import org.micromanager.data.DatastoreFrozenException;
import org.micromanager.data.DatastoreRewriteException;
import org.micromanager.data.Image;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.display.DisplayWindow;
import org.micromanager.internal.utils.MMDialog;

/**
 *
 * @author nico
 */
public class CropperPluginFrame extends MMDialog {
   private final Studio studio_;
   private final DisplayWindow ourWindow_;
   private final Datastore ourStore_;
   
   public CropperPluginFrame (Studio studio, DisplayWindow window) {
      studio_ = studio;
      final CropperPluginFrame cpFrame = this;
      
      super.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
      super.addWindowListener(new WindowAdapter() {
         @Override
         public void windowClosing(WindowEvent arg0) {
            cpFrame.dispose();
         }
      }
      );
      
      ourWindow_ = window;
      ourStore_ = ourWindow_.getDatastore();
      
      // Not sure if this is needed, be safe for now
      if (!ourStore_.getIsFrozen()) {
         studio_.logs().showMessage("Can not crop ongoing acquisitions");
         super.dispose();
         return;
      }
      
      super.setLayout(new MigLayout("flowx, fill, insets 8"));
      super.setTitle(CropperPlugin.MENUNAME);

      super.loadAndRestorePosition(100, 100, 375, 275);
      
      List<String> axes = ourStore_.getAxes();
      final Map<String, Integer> mins = new HashMap<String, Integer>();
      final Map<String, Integer> maxes = new HashMap<String, Integer>();
      
      super.add(new JLabel(" "));
      super.add(new JLabel("min"));
      super.add(new JLabel("max"), "wrap");
      
      for (final String axis : axes) {
         if (ourStore_.getAxisLength(axis) > 1) {
            mins.put(axis, 1);
            maxes.put(axis, ourStore_.getAxisLength(axis));
            
            super.add(new JLabel(axis));
            SpinnerNumberModel model = new SpinnerNumberModel((int) 1, (int) 1,
                    (int) ourStore_.getAxisLength(axis), (int) 1);
            final JSpinner minSpinner = new JSpinner(model);
            JFormattedTextField field = (JFormattedTextField) 
                    minSpinner.getEditor().getComponent(0);
            DefaultFormatter formatter = (DefaultFormatter) field.getFormatter();
            formatter.setCommitsOnValidEdit(true);
            minSpinner.addChangeListener(new ChangeListener(){
               @Override
               public void stateChanged(ChangeEvent ce) {
                  // check to stay below max
                  if ( (Integer) minSpinner.getValue() >= maxes.get(axis)) {
                     minSpinner.setValue(maxes.get(axis) - 1);
                  }
                  mins.put(axis, (Integer) minSpinner.getValue());
                  Coords coord = ourWindow_.getDisplayedImages().get(0).getCoords();
                  coord = coord.copy().index(axis, 
                          (Integer) minSpinner.getValue() - 1).build();
                  ourWindow_.setDisplayedImageTo(coord);
               }
            });
            super.add(minSpinner, "wmin 60");

            model = new SpinnerNumberModel((int) ourStore_.getAxisLength(axis), (int) 1,
                    (int) ourStore_.getAxisLength(axis), (int) 1);
            final JSpinner maxSpinner = new JSpinner(model);
            field = (JFormattedTextField) 
                    maxSpinner.getEditor().getComponent(0);
            formatter = (DefaultFormatter) field.getFormatter();
            formatter.setCommitsOnValidEdit(true);
            maxSpinner.addChangeListener(new ChangeListener(){
               @Override
               public void stateChanged(ChangeEvent ce) {
                  // check to stay above min
                  if ( (Integer) maxSpinner.getValue() <= mins.get(axis)) {
                     maxSpinner.setValue(mins.get(axis) + 1);
                  }
                  maxes.put(axis, (Integer) maxSpinner.getValue());
                  Coords coord = ourWindow_.getDisplayedImages().get(0).getCoords();
                  coord = coord.copy().index(axis, 
                          (Integer) maxSpinner.getValue() - 1).build();
                  ourWindow_.setDisplayedImageTo(coord);
               }
            });
            super.add(maxSpinner, "wmin 60, wrap");
         }
      }
      
      JButton OKButton = new JButton("OK");
      OKButton.addActionListener(new ActionListener() {
         @Override
         public void actionPerformed(ActionEvent ae) {
            crop(ourWindow_, mins, maxes);
            cpFrame.dispose();
         }
      });
      super.add(OKButton, "span 3, split 2, tag ok, wmin button");
      
      JButton CancelButton = new JButton("Cancel");
      CancelButton.addActionListener(new ActionListener(){
         @Override
         public void actionPerformed(ActionEvent ae) {
            cpFrame.dispose();
         }
      });
      super.add(CancelButton, "tag cancel, wrap");     
      
      super.pack();
      super.setVisible(true);
      
      
   }
   
   /**
    * Performs the actual creation of a new image with reduced content
    * 
    * @param theWindow - original window to be copied
    * @param mins - Map with new (or unchanged) minima for the given axis
    * @param maxes - Map with new (or unchanged) maxima for the given axis
    */
   public void crop(final DisplayWindow theWindow, 
           final Map<String, Integer> mins,
           final Map<String, Integer> maxes) {
      
      // TODO: provide options for disk-backed datastores
      Datastore newStore = studio_.data().createRAMDatastore();
      Datastore oldStore = theWindow.getDatastore();
      Coords oldSizeCoord = oldStore.getMaxIndices();
      CoordsBuilder newCoordsBuilder = oldSizeCoord.copy();
      SummaryMetadata metadata = oldStore.getSummaryMetadata();
      String[] channelNames = metadata.getChannelNames();
      if (mins.containsKey(Coords.CHANNEL) ) {
         int min = mins.get(Coords.CHANNEL);
         int max = maxes.get(Coords.CHANNEL); 
         if (min > 1 || max < channelNames.length) {
            List<String> chNameList = new ArrayList<String>();
            for (int index = min-1; index < max - 1; index++) {
               chNameList.add(channelNames[index]);
            }
            channelNames = chNameList.toArray(channelNames);
         }
      }
      newCoordsBuilder.channel(channelNames.length);
      String[] axes = {Coords.STAGE_POSITION, Coords.TIME, Coords.Z};
      for (String axis : axes) {
         if (mins.containsKey(axis)) {
            int min = mins.get(axis);
            int max = maxes.get(axis);
            newCoordsBuilder.index(axis, max - min + 1);
         }
      }

      metadata = metadata.copy()
              .channelNames(channelNames)
              .intendedDimensions(newCoordsBuilder.build())
              .build();
      try {
         newStore.setSummaryMetadata(metadata);

         Iterable<Coords> unorderedImageCoords = oldStore.getUnorderedImageCoords();
         for (Coords oldCoord : unorderedImageCoords) {
            boolean copy = true;
            for (String axis : oldCoord.getAxes()) {
               if (oldCoord.getIndex(axis) < (mins.get(axis) -1) )
                  copy = false;
               if (oldCoord.getIndex(axis) >= maxes.get(axis) ) 
                  copy = false;
            }
            if (copy) {
               CoordsBuilder newCoordBuilder = oldCoord.copy();
               for (String axis : oldCoord.getAxes()) {
                  newCoordBuilder.index(axis, oldCoord.getIndex(axis) - mins.get(axis));
               }
               Image img = oldStore.getImage(oldCoord);
               Image newImgShallow = img.copyAtCoords(newCoordBuilder.build());
               newStore.putImage(newImgShallow);
            }
         }

      } catch (DatastoreFrozenException ex) {
         // TODO
      } catch (DatastoreRewriteException ex) {
         // TODO
      }
   }
   
}
