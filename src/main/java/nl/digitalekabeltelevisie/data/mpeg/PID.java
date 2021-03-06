/**
 *
 *  http://www.digitalekabeltelevisie.nl/dvb_inspector
 *
 *  This code is Copyright 2009-2012 by Eric Berendsen (e_berendsen@digitalekabeltelevisie.nl)
 *
 *  This file is part of DVB Inspector.
 *
 *  DVB Inspector is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DVB Inspector is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DVB Inspector.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  The author requests that he be notified of any application, applet, or
 *  other binary that makes use of this code, but that's more out of curiosity
 *  than anything and is not required.
 *
 */

package nl.digitalekabeltelevisie.data.mpeg;

import static nl.digitalekabeltelevisie.data.mpeg.MPEGConstants.system_clock_frequency;
import static nl.digitalekabeltelevisie.util.Utils.getUnsignedByte;
import static nl.digitalekabeltelevisie.util.Utils.printPCRTime;

import java.util.Formatter;
import java.util.logging.Logger;

import javax.swing.JMenuItem;
import javax.swing.tree.DefaultMutableTreeNode;

import nl.digitalekabeltelevisie.controller.KVP;
import nl.digitalekabeltelevisie.controller.TreeNode;
import nl.digitalekabeltelevisie.data.mpeg.pes.GeneralPesHandler;
import nl.digitalekabeltelevisie.data.mpeg.psi.GeneralPSITable;
import nl.digitalekabeltelevisie.data.mpeg.psi.MegaFrameInitializationPacket;
import nl.digitalekabeltelevisie.util.JTreeLazyList;
import nl.digitalekabeltelevisie.util.PIDPacketGetter;

/**
 * Collects all {@link TSPacket}s with same packet_id, groups them together, and interprets them depending on type. For PSI packets tables are built, PES packets are (initially) only counted.
 * Does not store all data packets for this PID
 */
public class PID implements TreeNode{
	private static final Logger logger = Logger.getLogger(PID.class.getName());

	public static final int PES = 1;
	public static final int PSI = 2;
	private int type=0;
	private boolean scrambled = false;

	private long bitRate = -1;

	/**
	 * if this PID is of type PSI, this table is used as a general representation of its data.
	 */
	private GeneralPSITable psi ;


	/**
	 * generalPesHandler that is able to interpret the PES_packet_data_byte, and turn it into something we can display
	 */
	private GeneralPesHandler generalPesHandler=null;

	/**
	 * number of TS packets in this PID
	 */
	private long packets = 0;
	/**
	 * number of duplicate packets
	 */
	private long dup_packets = 0;
	/**
	 *  number of continuity_errors
	 */
	private long continuity_errors = 0;
	private int last_continuity_counter = -1;
	private int pid = -1;
	private long last_packet_no=-1;
	private TSPacket last_packet = null;
	private boolean dup_found=false;
	private PCR lastPCR;
	private PCR firstPCR;
	private long lastPCRpacketNo = -1;
	private long firstPCRpacketNo =-1;
	private long pcr_count =-1;
	protected TransportStream parentTransportStream = null;

	private String label=null;
	private String shortLabel=null;

	private final GatherPIDData gatherer = new GatherPIDData();

	/**
	 *
	 * This inner class is a helper that collects and groups TSPackets for the containing PID into PsiSectionData's . If this PID contains PES data, the bytes are ignored.
	 * @author Eric Berendsen
	 *
	 */
	public class GatherPIDData {


		private PsiSectionData lastPSISection;

		public void reset(){
			lastPSISection = null;

		}

		public void processPayload(final TSPacket packet, final TransportStream ts, final PID parentPID)
		{
			parentTransportStream = ts;
			final byte []data = packet.getData();
			if((lastPSISection==null)){ // nothing started
				// sometimes PayloadUnitStartIndicator is 1, and there is no payload, so check AdaptationFieldControl
				if(packet.isPayloadUnitStartIndicator() &&
						(data.length>1) &&
						((packet.getAdaptationFieldControl()==1)||(packet.getAdaptationFieldControl()==3))){ //start something
					// at least one byte plus pointer available
					int start;
					int available;
					if((data[0]!=0)||((getPid() ==0)||(data[1]!=0))){ //starting PSI section after ofset
						// this is just an educated guess, it might still be private data of unspecified format
						type = PSI;

						start = 1+getUnsignedByte(data[0]);
						available = data.length -start;
						while ((available>0) && (getUnsignedByte(data[start])!= 0xFF)){
							lastPSISection = new PsiSectionData(parentPID,packet.getPacketNo(),parentTransportStream);
							final int bytes_read=lastPSISection.readBytes(data, start, available);
							start+=bytes_read;
							available-=bytes_read;
						}

						//	 could be starting PES stream, make sure it really is, Should start with packet_start_code_prefix -'0000 0000 0000 0000 0000 0001' (0x000001)
					}else if((data.length>2) &&
							(data[0]==0)&&(data[1]==0)&&(data[2]==1)){
						type = PES;

					}
				}
				//	something started
			}else if((packet.getAdaptationFieldControl()==1)||(packet.getAdaptationFieldControl()==3)){ // has payload?
				// are we in a PSI PID??
				if(type==PSI){
					int start;
					if(packet.isPayloadUnitStartIndicator()){ //first byte is pointer, skip pointer and continue with what we already got from previous TSPacket
						start = 1;
					}else{
						start = 0;
					}
					int available = data.length -start;
					if(!lastPSISection.isComplete()){
						final int bytes_read=lastPSISection.readBytes(data, start, available);
						start+=bytes_read;
						available-=bytes_read;
					}
					while ((available>0) && (getUnsignedByte(data[start])!= 0xFF)){
						lastPSISection = new PsiSectionData(parentPID,packet.getPacketNo(),parentTransportStream);
						final int bytes_read=lastPSISection.readBytes(data, start, available);
						start+=bytes_read;
						available-=bytes_read;
					}
				}
			}
		}
	}



	/**
	 * @param pid packet_id of packets in this PID
	 * @param ts
	 */
	public PID(final int pid, final TransportStream ts) {
		this.pid=pid;
		parentTransportStream = ts;
		psi = new GeneralPSITable(ts.getPsi());

	}

	public void update_packet(final TSPacket packet) {
		// handle 0x015 Mega-frame Initialization Packet (MIP)
		if(pid==0x015){
			// MIP has only TSPackets, no structure with PSISectionData
			if((packet.getData()!=null)&&(packet.getData().length>=14)){
				final MegaFrameInitializationPacket mip= new MegaFrameInitializationPacket(packet);
				parentTransportStream.getPsi().getNetworkSync().update(mip);
			}
		}else{

			processAdaptationField(packet);
			if(((last_continuity_counter==-1)|| // first packet
					(pid==0x1fff)|| // null packet
					(((last_continuity_counter+1)%16)==packet.getContinuityCounter()))
					) {
				// counter ok
				last_continuity_counter = packet.getContinuityCounter();
				last_packet_no = packet.getPacketNo();
				last_packet = packet;
				dup_found = false;

				if(packet.getTransportScramblingControl()==0){ // not scrambled, or else payload is of no use

					gatherer.processPayload(packet,parentTransportStream,this);
				}else{
					scrambled=true;
				}

			}else if(last_continuity_counter==packet.getContinuityCounter()){
				if(dup_found){ // second  dup packet (third total), illegal
					logger.fine("second  dup packet (third total), illegal, PID="+pid+", last="+last_continuity_counter+", new="+packet.getContinuityCounter()+", last_no="+last_packet_no +", packet_no="+packet.getPacketNo());
				}else{ // just a dup, count it and ignore
					dup_found = true;
					dup_packets++;
				}

			}else 	{
				logger.warning("continuity error, PID="+pid+", last="+last_continuity_counter+", new="+packet.getContinuityCounter()+", last_no="+last_packet_no +", packet_no="+packet.getPacketNo());
				last_continuity_counter=-1;
				continuity_errors++;
				gatherer.reset();
			}
		}
		packets++;
	}

	private void processAdaptationField(final TSPacket packet) {
		AdaptationField adaptationField = null;
		try{
			adaptationField = packet.getAdaptationField();
		}catch(final RuntimeException re){ // might be some error in adaptation field, it is not well protected
			adaptationField = null;
		}
		if(adaptationField!=null) { //Adaptation field present
			if(adaptationField.isPCR_flag()){
				final PCR newPCR = adaptationField.getProgram_clock_reference();
				if((firstPCR != null)&&!adaptationField.isDiscontinuity_indicator()){
					final long packetsDiff = packet.getPacketNo() - firstPCRpacketNo;

					// This will ignore single PCR packets that have lower values than previous.
					// when PCR wraps around we only use first part till wrap around for bitrate calculation
					// (unless PCR reaches value of firstPCR again, this would mean stream of > 24 hours)
					if((newPCR.getProgram_clock_reference()- firstPCR.getProgram_clock_reference()) >0){
						bitRate = ((packetsDiff * parentTransportStream.getPacketLenghth() * system_clock_frequency * 8))/(newPCR.getProgram_clock_reference()- firstPCR.getProgram_clock_reference());
					}
					lastPCR = newPCR;
					lastPCRpacketNo = packet.getPacketNo();
					pcr_count++;

				}else{ // start, or restart of discontinuity
					firstPCR = newPCR;
					firstPCRpacketNo = packet.getPacketNo();
					lastPCR = null;
					lastPCRpacketNo = -1;
					pcr_count=1;
				}
			}
		}
	}

	/**
	 * @return number of TS packets found for this PID
	 */
	public long getPackets() {
		return packets;
	}
	@Override
	public String toString() {
		return "PID:"+pid+", packets:"+packets;
	}

	public int getPid() {
		return pid;
	}

	public boolean isDup_found() {
		return dup_found;
	}

	public long getDup_packets() {
		return dup_packets;
	}

	public int getLast_continuity_counter() {
		return last_continuity_counter;
	}

	public TSPacket getLast_packet() {
		return last_packet;
	}

	public long getLast_packet_no() {
		return last_packet_no;
	}

	public TransportStream getParentTransportStream() {
		return parentTransportStream;
	}

	public void setParentTransportStream(final TransportStream parentTransportStream) {
		this.parentTransportStream = parentTransportStream;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(final String label) {
		this.label = label;
	}

	public DefaultMutableTreeNode getJTreeNode(final int modus) {
		final KVP kvp=new KVP("pid",getPid(),getLabel());
		if((generalPesHandler!=null)&&(!scrambled)){
			final JMenuItem pesMenu = new JMenuItem("Parse PES data");
			pesMenu.setActionCommand("parse");
			kvp.setSubMenuAndOwner(pesMenu,this);
		}
		final DefaultMutableTreeNode t = new DefaultMutableTreeNode(kvp);

		t.add(new DefaultMutableTreeNode(new KVP("packets",getPackets(),null)));
		t.add(new DefaultMutableTreeNode(new KVP("duplicate packets",dup_packets,null)));
		t.add(new DefaultMutableTreeNode(new KVP("continuity errors",continuity_errors,null)));


		t.add(new DefaultMutableTreeNode(new KVP("transport_scrambling_control",(scrambled)?"true":"false",null)));
		if(!scrambled){
			t.add(new DefaultMutableTreeNode(new KVP("type",(type==PSI)?"PSI":((type==PES)?"PES":"-"),null)));
		}
		if(firstPCR!=null){
			t.add(new DefaultMutableTreeNode(new KVP("First PCR",firstPCR.getProgram_clock_reference(),printPCRTime(firstPCR.getProgram_clock_reference()))));
			t.add(new DefaultMutableTreeNode(new KVP("First PCR packet",firstPCRpacketNo,getParentTransportStream().getPacketTime(firstPCRpacketNo))));
		}
		if(lastPCR!=null){
			t.add(new DefaultMutableTreeNode(new KVP("Last PCR",lastPCR.getProgram_clock_reference(),printPCRTime(lastPCR.getProgram_clock_reference()))));
			t.add(new DefaultMutableTreeNode(new KVP("Last PCR packet",lastPCRpacketNo, getParentTransportStream().getPacketTime(lastPCRpacketNo))));
			t.add(new DefaultMutableTreeNode(new KVP("PCR_count",pcr_count ,getRepetitionRate(pcr_count,lastPCRpacketNo,firstPCRpacketNo))));
		}
		if(bitRate!= -1){
			t.add(new DefaultMutableTreeNode(new KVP("TS bitrate based on PCR",bitRate,null)));
		}
		if(type==PSI){
			t.add(psi.getJTreeNode(modus));
		}else if((type==PES)&&(generalPesHandler!=null)&&(generalPesHandler.isInitialized())) {
			t.add(((TreeNode)generalPesHandler).getJTreeNode(modus));
		}
		if(parentTransportStream.isEnableTSPackets()&&parentTransportStream.tsPacketsLoaded()){

			final JTreeLazyList list = new JTreeLazyList(new PIDPacketGetter(parentTransportStream,pid,modus));
			t.add(list.getJTreeNode(modus, "Transport packets "));

		}



		return t;
	}

	public GeneralPSITable getPsi() {
		return psi;
	}

	private String getRepetitionRate(final long count,final long last, final long  first) {
		final long bitrate=getParentTransportStream().getBitRate();
		if((bitrate>0)&&(count>=2)){
			@SuppressWarnings("resource")
			final Formatter formatter = new Formatter();
			final float repRate=((float)(last-first)*parentTransportStream.getPacketLenghth()*8)/((count-1)*bitrate);
			return "repetition rate: "+formatter.format("%3.3f seconds",repRate);
		}
		return null;
	}

	public void setPsi(final GeneralPSITable psi) {
		this.psi = psi;
	}

	public String getShortLabel(){
		return shortLabel;
	}

	public void setShortLabel(final String shortLabel) {
		this.shortLabel = shortLabel;
	}

	public long getBitRate() {
		return bitRate;
	}


	/**
	 * @return the generalPesHandler
	 */
	public GeneralPesHandler getPesHandler() {
		return generalPesHandler;
	}

	/**
	 * @param generalPesHandler the generalPesHandler to set
	 */
	public void setPesHandler(final GeneralPesHandler abstractPesHandler) {
		this.generalPesHandler = abstractPesHandler;
	}

	/**
	 * used when PID is already initiated, contains PES data and is not scrambled. Now we want to process only the actual PES data (may be very memory intensive)
	 */
	public void gatherPESPackets(final TSPacket packet) {

		if(((last_continuity_counter==-1)|| // first packet
				(pid==0x1fff)|| // null packet
				(((last_continuity_counter+1)%16)==packet.getContinuityCounter()))
				) {
			// counter klopt
			last_continuity_counter = packet.getContinuityCounter();
			last_packet_no = packet.getPacketNo();
			last_packet = packet;
			dup_found = false;

			if(packet.getTransportScramblingControl()==0){ // not scrambled, or else payload is of no use

				gatherer.processPayload(packet,parentTransportStream,this);
			}else{
				scrambled=true;
			}

		}else if(last_continuity_counter==packet.getContinuityCounter()){
			if(dup_found){ // second  dup packet (third total), illegal
				logger.fine("second  dup packet (third total), illegal, PID="+pid+", last="+last_continuity_counter+", new="+packet.getContinuityCounter()+", last_no="+last_packet_no +", packet_no="+packet.getPacketNo());
			}else{ // just a dup, count it and ignore
				dup_found = true;
				dup_packets++;
			}

		}else 	{
			logger.fine("continuity error, PID="+pid+", last="+last_continuity_counter+", new="+packet.getContinuityCounter()+", last_no="+last_packet_no +", packet_no="+packet.getPacketNo());
			last_continuity_counter=-1;
			continuity_errors++;
			gatherer.reset();
		}

	}

	public int getType() {
		return type;
	}

	public boolean isScrambled() {
		return scrambled;
	}

	public GeneralPesHandler getGeneralPesHandler() {
		return generalPesHandler;
	}

	public long getContinuity_errors() {
		return continuity_errors;
	}

	public PCR getLastPCR() {
		return lastPCR;
	}

	public PCR getFirstPCR() {
		return firstPCR;
	}

	public long getLastPCRpacketNo() {
		return lastPCRpacketNo;
	}

	public long getFirstPCRpacketNo() {
		return firstPCRpacketNo;
	}

	public long getPcr_count() {
		return pcr_count;
	}

	public GatherPIDData getGatherer() {
		return gatherer;
	}

}
