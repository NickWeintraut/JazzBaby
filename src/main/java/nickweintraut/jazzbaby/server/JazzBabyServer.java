/*
 * Copyright 2013 Per Wendel
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nickweintraut.jazzbaby.server;

import java.io.StringReader;
import java.util.HashMap;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;

import javax.json.*;
import javax.json.stream.JsonParser;

import org.codehaus.plexus.util.StringInputStream;

import architecture.FragmentedNeuralQueue;
import architecture.poex.ProductCompressingAutoencoder;
import encoding.ChordEncoder;
import encoding.EncodingParameters;
import encoding.NoteEncoder;
import io.leadsheet.Chord;
import io.leadsheet.Constants;
import io.leadsheet.LeadsheetDataSequence;
import io.leadsheet.NoteSymbol;
import main.Driver;
import mikera.vectorz.AVector;
import mikera.vectorz.Vector;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;

import static spark.Spark.*;

/**
 * VelocityTemplateRoute example.
 */
public final class JazzBabyServer {
	
	static String connectomePath = "";
	static boolean variational = false;
	static ProductCompressingAutoencoder autoencoder = setupAutoencoder();
	
    public static void main(String[] args) {

    	staticFileLocation("/public");
    	
        get("/", (request, response) -> {
            Map<String, Object> model = new HashMap<>();
            //model.put("message", "Hello Velocity");
            return new ModelAndView(model, "index.html"); // located in the resources directory
        }, new VelocityTemplateEngine());
        
        get("/merge", (request, response) -> {
        	
        	JsonReader reader = Json.createReader(new StringReader(request.body()));
        	JsonArray melodyJsons = reader.readArray();
        	LeadsheetDataSequence[] sequences = parseInput(melodyJsons.getJsonObject(0), melodyJsons.getJsonObject(1), melodyJsons.getJsonObject(2));
        	autoencoder.setQueue(new FragmentedNeuralQueue());
        	Driver.encodeFromSequence(autoencoder, sequences[0]);
        	FragmentedNeuralQueue queue1 = autoencoder.getQueue();
        	autoencoder.setQueue(new FragmentedNeuralQueue());
        	Driver.encodeFromSequence(autoencoder, sequences[1]);
        	FragmentedNeuralQueue queue2 = autoencoder.getQueue();
        	//find a halfway point between the queues!
        	queue1.basicInterpolate(queue2, 0.5);
        	autoencoder.setQueue(queue1);
        	LeadsheetDataSequence outSequence = sequences[2];
        	Driver.decodeToSequence(autoencoder, outSequence, outSequence.copy());
        	//return the contents in Json of the result sequence!
        	return "";
        	});
    }
    
    public static LeadsheetDataSequence[] parseInput(JsonObject melody1, JsonObject melody2, JsonObject outChordSequence) throws DataFormatException {
    	JsonArray chords1 = melody1.getJsonArray("chords");
    	JsonArray notes1 = melody1.getJsonArray("notes");
    	LeadsheetDataSequence sequence1 = getDataSequence(chords1, notes1);
    	
    	JsonArray chords2 = melody2.getJsonArray("chords");
    	JsonArray notes2 = melody2.getJsonArray("notes");
    	LeadsheetDataSequence sequence2 = getDataSequence(chords2, notes2);
    	
    	JsonArray chords3 = outChordSequence.getJsonArray("chords");
    	JsonArray notes3 = outChordSequence.getJsonArray("notes");
    	LeadsheetDataSequence outputChords = getDataSequence(chords3, notes3);
    	
    	return new LeadsheetDataSequence[]{sequence1, sequence2, outputChords};
    }
    
    public static LeadsheetDataSequence getDataSequence(JsonArray chords, JsonArray notes) throws DataFormatException{
    	LeadsheetDataSequence sequence = new LeadsheetDataSequence();
    	if(chords.size() != 4)
    		throw new DataFormatException("There are " + chords.size() + "chords in a melody, when there should only be 4.");
    	
    	
    	NoteEncoder noteEncoder = EncodingParameters.noteEncoder;
    	int noteSteps = 0;
    	for (int i = 0; i < notes.size(); i++) {
    		JsonString noteString = notes.getJsonString(i);
    		NoteSymbol note = NoteSymbol.makeNoteSymbol(noteString.getString().trim());
    		
    		if(note.isRest())
            {
                AVector encoding = noteEncoder.encode(note.getMIDI());
                for(int remaining = (note.getDuration() /Constants.RESOLUTION_SCALAR); remaining > 0 ; remaining--) {
                    sequence.pushStep(null, null, encoding);
                    noteSteps++;
                }
            }
            else
            {
                noteSteps++;
                sequence.pushStep(null, null, noteEncoder.encode(note.getMIDI()));
                for(int remaining = (note.getDuration() /Constants.RESOLUTION_SCALAR) - 1; remaining > 0 ; remaining--) {
                    sequence.pushStep(null, null, noteEncoder.encode(noteEncoder.getSustainKey()));
                    noteSteps++;
                }
            }
    	}
    
    	ChordEncoder chordEncoder = EncodingParameters.chordEncoder;
    	Pattern p = Pattern.compile("([A-G](?:#|b)?)([^/]*)(?:/(.+))?");
    	int chordSteps = 0;
    	for(int i = 0; i < chords.size(); i++){
    		JsonString chordString = chords.getJsonString(i);
    		String actualChordString = chordString.getString().trim();
    		Chord chord;
            if(actualChordString.equals("NC")) {
                chord = new Chord(0,"NC","NC");
            } else {
                Matcher m = p.matcher(actualChordString);
                if(m.matches()){
                    String root = m.group(1);
                    String type = m.group(2);
                    String slash_bass = m.group(3);
                    chord = new Chord(Constants.WHOLE/Constants.RESOLUTION_SCALAR,root,type,slash_bass);
                    
                } else {
                    throw new DataFormatException("Malformed chord symbol " + actualChordString);
                }
            }
    		
    		AVector chordData = chordEncoder.encode(chord.getRoot(), chord.getType(), chord.getBass());

            //System.out.println(chordData);
            for(int remaining = chord.getDuration(); remaining > 0; remaining--) {
                chordSteps++;
                sequence.pushStep(null, chordData.copy(), null);
            }
    	}
    	
    	for(int timeStep = 0; timeStep < noteSteps; timeStep++)
        {
            AVector beat = Vector.createLength(9);
            if(timeStep % (Constants.WHOLE / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(0, 1.0);
            if(timeStep % (Constants.HALF / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(1, 1.0);
            if(timeStep % (Constants.QUARTER / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(2, 1.0);
            if(timeStep % (Constants.EIGHTH / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(3, 1.0);
            if(timeStep % (Constants.SIXTEENTH / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(4, 1.0);
            if(timeStep % (Constants.HALF_TRIPLET / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(5, 1.0);
            if(timeStep % (Constants.QUARTER_TRIPLET / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(6, 1.0);
            if(timeStep % (Constants.EIGHTH_TRIPLET / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(7, 1.0);
            if(timeStep % (Constants.SIXTEENTH_TRIPLET / Constants.RESOLUTION_SCALAR) == 0)
                beat.set(8, 1.0);
            sequence.pushStep(beat, null, null);    
        }
    	return sequence;
    }
    
    public static ProductCompressingAutoencoder setupAutoencoder(){
    	return Driver.initializeAutoencoder(connectomePath, variational);
    }

}
