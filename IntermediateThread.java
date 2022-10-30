import java.util.*; 
import java.net.*; 
import java.io.*; 
import java.util.concurrent.locks.*; 

// this thread is responsible for creating threads to send request to each member
// and notify the CouncilMember when the response list is ready
public class IntermediateThread implements Runnable{
    private CouncilMember member; 
    private HashMap<String, Object> requestObject; 
    private static final int STARTING_PORT = 2000; 
    private static final long MAX_WAITING_MILLIS = 13000; // all the messages should be completed within 13 seconds

    // IntermediateThread constructor
    // input: CouncilMember (the member/proposer which send the request), HashMap<String, Object> (request object to send to other members)
    // output: no 
    public IntermediateThread(CouncilMember member, HashMap<String, Object> requestObject){
        this.member = member; 
        this.requestObject = requestObject; 
    }

    // send requests to all members in the protocol
    // input: no
    // output: no
    @Override
    public void run(){
        ArrayList<Thread> childThreadList = new ArrayList<Thread>(); 
        // for each member in the protocol
        // create a thread to send request 
        for(int i = 0; i < this.member.getMemberServerSocketList().size(); i++){
            String serverSocketInfo = this.member.getMemberServerSocketList().get(i); 
            // hostname is from the start of the string to the ':' character
            String hostname = serverSocketInfo.substring(0, serverSocketInfo.indexOf(':')); 
            // port is from character ':' + 1 to the end of the string
            int port = Integer.parseInt(serverSocketInfo.substring(serverSocketInfo.indexOf(':') + 1));
            ProposerSendRequest sendRequest = new ProposerSendRequest(this.member, hostname, port, this.requestObject); 
            Thread sendingRequest = new Thread(sendRequest); 
            childThreadList.add(sendingRequest); 
            sendingRequest.start(); 
        }
        String requestType = (String)this.requestObject.get("Request-Type"); 
        if(requestType.equals("Decide") == false){
            for(int i = 0; i < childThreadList.size(); i++){
                try{
                    // wait for all the sending thread complete 
                    childThreadList.get(i).join(MAX_WAITING_MILLIS); 
                }
                catch(Exception e){
                    System.out.println("Error in Intermediate Thread"); 
                    e.printStackTrace(); 
                }
            }
            this.member.getResponseListStateLock().lock(); 
            this.member.setResponseListState(true); 
            // notify CouncilMember when the response list is ready
            this.member.getResponseListReady().signal(); 
            this.member.getResponseListStateLock().unlock(); 
        }
    }
}
