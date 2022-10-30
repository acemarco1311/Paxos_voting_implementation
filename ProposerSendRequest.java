import java.util.*;
import java.net.*; 
import java.io.*; 
import java.util.concurrent.locks.*; 

// this thread is responsible to send a request to a specific member
public class ProposerSendRequest implements Runnable{ 
    private static final int TIMEOUT_MILLIS = 12000; // timeout = 12 seconds, > than medium (5s) but < late (15s)
    private CouncilMember member; // sender
    private String hostname; // hostname of the receiver
    private int port; // port of the receiver
    private HashMap<String, Object> requestObject; // the object to send 

    // Thread constructor
    // input: CouncilMember (the sender who sending this request), String (hostname of targeted member) 
    //        int (port of the targeted member), HashMap<String, Object> (request to send) 
    // output: no
    public ProposerSendRequest(CouncilMember member, String hostname, int port, HashMap<String, Object> requestObject){
        this.member = member; 
        this.hostname = hostname; 
        this.port = port; 
        this.requestObject = requestObject; 
    }

    // send the request to the targeted member
    // input: no
    // output: no 
    @Override
    public void run(){
        try{
            Socket socket = new Socket(); 
            InetAddress address = InetAddress.getByName(this.hostname); 
            SocketAddress serverAddress = new InetSocketAddress(address, this.port); 
            socket.connect(serverAddress, TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream()); 
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream()); 
            // send request
            out.writeObject(this.requestObject); 
            // read response 
            HashMap<String, Object> responseObject = (HashMap<String, Object>)in.readObject(); 
            if(responseObject.containsKey("Status") && ((String)responseObject.get("Status")).equals("Promise-OK")){
                int responserId = (int)responseObject.get("Sender-Id"); 
                int proposalId = (int)responseObject.get("Current-Proposal-Id"); 
                int requesterId = (int)this.requestObject.get("Sender-Id"); 
                // indicate that response has been received
                System.out.println("Council Member " + responserId + " Promised on proposal id " + proposalId + " to Council Member " + requesterId + "."); 
            }
            else if(responseObject.containsKey("Status") && ((String)responseObject.get("Status")).equals("Accept-OK")){
                int responserId = (int)responseObject.get("Sender-Id"); 
                Proposal proposal= (Proposal)responseObject.get("Accepted-Proposal"); 
                String proposalValue = proposal.getValue(); 
                int requesterId = (int)this.requestObject.get("Sender-Id"); 
                // indicate that the response has been received
                System.out.println("Council Member " + responserId + " Accepted proposal from Council Member " + requesterId + " on value " + proposalValue + "."); 
            }
            this.member.getResponseListLock().lock(); 
            // add the response to response list
            this.member.getResponseList().add(responseObject); 
            this.member.getResponseListLock().unlock(); 
            out.flush(); 
            socket.close(); 
        }
        catch(Exception e){

        }
    }
}
