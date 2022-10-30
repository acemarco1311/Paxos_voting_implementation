import java.util.*; 
import java.net.*; 
import java.io.*; 
import java.util.concurrent.locks.*; 

// this thread is responsible for responding to requests (Prepare/Accept/Decide) from the Proposer
public class AcceptorResponseToRequest implements Runnable{
    private static int MEDIUM_LATENCY = 5000; // medium latency, respond after 5s
    private static int LATE_LATENCY = 15000; // late latency, respond after 15s 
    private Socket senderSocket;
    private ObjectOutputStream out; 
    private ObjectInputStream in; 
    private CouncilMember member; 

    // Thread constructor
    // input: CouncilMember, Socket (socket of Proposer who send the request)
    // output: no
    public AcceptorResponseToRequest(CouncilMember member, Socket socket){
        this.senderSocket = socket; 
        this.member = member; 
        try{
            this.out = new ObjectOutputStream(socket.getOutputStream()); 
            this.in = new ObjectInputStream(socket.getInputStream()); 
        }
        catch(Exception e){ 
            System.out.println("Error when Acceptor responding to request."); 
            e.printStackTrace(); 
        }
    }

    // this function is responsible for responding to a request from the Proposer
    // input: no
    // output: no 
    @Override 
    public void run(){
        try{
            // check the request object 
            HashMap<String, Object> requestObject = (HashMap<String, Object>)this.in.readObject(); 
            String requestType = (String)requestObject.get("Request-Type"); 
            HashMap<String, Object> responseObject = new HashMap<String, Object>(); 
            // if the request is a Prepare request
            if(requestType.equals("Prepare")){
                // announce that this Acceptor has received the request
                System.out.println("Council Member " + this.member.getMemberId() + " received Prepare request from Council Member " + (int)requestObject.get("Sender-Id") + "."); 
                int proposalId = (int)requestObject.get("Current-Proposal-Id"); 
                // call sub-function to create a response object to send back
                responseObject = handlePrepareRequest(proposalId, requestObject); 
            }
            // if the request is a Accept request 
            else if(requestType.equals("Accept")){
                // announce that the Acceptor has received the request 
                System.out.println("Council Member " + this.member.getMemberId() + " received Accept request from Council Member " + (int)requestObject.get("Sender-Id") + "."); 
                Proposal proposal = (Proposal)requestObject.get("Current-Proposal"); 
                // call sub-function to create a response object to send back 
                responseObject = handleAcceptRequest(proposal, requestObject); 
            }
            // if the request is a Decide request 
            else if(requestType.equals("Decide")){
                Proposal proposal = (Proposal)requestObject.get("Current-Proposal"); 
                // call sub-function to handle
                handleDecideRequest(proposal); 
            }
            // if the Acceptor is a Medium member, send response back 5 seconds after received the request 
            if(this.member.getLatencyType().equals("Medium")){
                try{
                    Thread.sleep(MEDIUM_LATENCY); 
                }
                catch(Exception e){

                }
            }
            // delay 15 seconds if the profile is late
            else if(this.member.getLatencyType().equals("Late")){
                try{
                    Thread.sleep(LATE_LATENCY); 
                }
                catch(Exception e){

                }
            }
            // only send the message back when the latency profile is not Never
            if(!this.member.getLatencyType().equals("Never")){
                // send response back 
                this.out.writeObject(responseObject); 
            }
            this.out.flush(); 
            this.senderSocket.close(); 
        }
        // if reply late, then the socket has been closed on the server-side
        // this is an acceptable
        catch(SocketException se){

        }
        catch(Exception e){
            System.out.println("Error when Acceptor responding to request."); 
            e.printStackTrace(); 
        }
    }

    // Phase 1: Acceptor responses to Prepare request
    // this function will create a response to a Prepare request from the Proposer. 
    // input: Integer (proposalId that the Proposer sent), HashMap<String, Object> (the request object sent by the Proposer) 
    // output:HashMap<String, Object> (the response object to send back to Proposer)
    public HashMap<String, Object> handlePrepareRequest(int proposalId, HashMap<String, Object> requestObject){
        // acquire the lock first 
        this.member.getLastAcceptedProposalLock().lock(); 
        this.member.getHighestProposalIdLock().lock(); 
        // create a response object 
        HashMap<String, Object> prepareResponse = new HashMap<String, Object>(); 
        prepareResponse.put("Response-Type", "Prepare"); 
        prepareResponse.put("Sender-Id", this.member.getMemberId()); 
        // the id of the given proposal < the highest id so far
        // so we send reject 
        if(this.member.getHighestProposalId() > proposalId){
            prepareResponse.put("Status", "Reject"); 
        }
        // if the proposalId is the highest so far
        else{
            this.member.setHighestProposalId(proposalId); 
            // if the acceptor has not accepted any proposal
            // send Promise for the this proposal id
            if(this.member.getLastAcceptedProposal() == null){
                prepareResponse.put("Status", "Promise-OK"); 
                prepareResponse.put("Current-Proposal-Id", proposalId); 
            }
            // if the acceptor has accepted a proposal before
            // send promise and send the lastAcceptedProposal to the proposer
            // so that the proposer can use the value of the lastAcceptedProposal
            else{
                prepareResponse.put("Status", "Promise-OK"); 
                prepareResponse.put("Current-Proposal-Id", proposalId); 
                prepareResponse.put("Last-Accepted-Proposal", this.member.getLastAcceptedProposal()); 
            }
        }
        this.member.getLastAcceptedProposalLock().unlock(); 
        this.member.getHighestProposalIdLock().unlock(); 
        return prepareResponse; 
    }
    
    // this function is responsible for creating a response object to respond to Accept request from the Proposer
    // input: Proposal(the proposal that Proposer sent), HasMap<String, Object> (the accept request object that the Proposer sent)
    // output: HashMap<String, Object> (the Accept response object to send back to the Proposer) 
    public HashMap<String, Object> handleAcceptRequest(Proposal newProposal, HashMap<String, Object> requestObject){
        // acquire the lock first 
        this.member.getLastAcceptedProposalLock().lock(); 
        this.member.getHighestProposalIdLock().lock(); 
        // create response object 
        HashMap<String, Object> acceptResponse = new HashMap<String, Object>(); 
        acceptResponse.put("Response-Type", "Accept"); 
        acceptResponse.put("Sender-Id", this.member.getMemberId()); 
        // accept the proposal if the proposal has the id = the highest id has seen so far
        if(newProposal.getID() == this.member.getHighestProposalId()){
            this.member.setLastAcceptedProposal(newProposal); 
            acceptResponse.put("Status", "Accept-OK"); 
            acceptResponse.put("Accepted-Proposal", this.member.getLastAcceptedProposal()); 
        }
        // otherwise, reject the Accept request 
        else{
            acceptResponse.put("Status", "Reject"); 
        }
        this.member.getLastAcceptedProposalLock().unlock(); 
        this.member.getHighestProposalIdLock().unlock(); 
        return acceptResponse; 
    }

    // if the Acceptor receive a Decide request from a Proposer
    // means that there is an agreement on a value 
    // the Acceptor will not send anything back 
    // but the Acceptor will announce which propsoser is the current leader and also which value that the majority agreed on
    // input: Proposal (the proposal that the Proposer sent)
    // output: no
    public void handleDecideRequest(Proposal proposal){
        String value = proposal.getValue(); 
        int memberId = proposal.getMemberID(); 
        System.out.println("Council Member " + memberId + " is the leader with value " + value); 
    }
}
