import java.util.*; 
import java.lang.Math; 
import java.net.*; 
import java.io.*; 
import java.util.concurrent.locks.*; 

public class CouncilMember implements Runnable{
    private static int ID = 0; 
    private static final int STARTING_PORT = 2000; // member 1 listens on port 2001, member 2 listens on port 2002, etc.
    private int memberId; 
    // listen on request from proposers
    private ServerSocket memberServerSocket; 
    // member acts as acceptor
    private Proposal lastAcceptedProposal; // store the Proposal that the Acceptor has accepted. 
    private int highestProposalId; // highest proposal id that the Acceptor has seen so far
    // server socket of all members 
    private ArrayList<String> memberServerSocketList; 
    // store all the server socket info of all members in the protocol
    // size of this array is also the total number of members in the protocol
    // members' server socket info is a string in format: "hostname:port"
    // also including server socket of this member
    private int majority; // majority can be extracted from size of memberServerSocketList
    private ArrayList<HashMap<String, Object>> responseList; 
    // after send a request to all the members, all the responses will be stored here
    private ReentrantLock lastAcceptedProposalLock; 
    private ReentrantLock highestProposalIdLock; 
    private ReentrantLock responseListLock; 
    private boolean responseListState; // indicate if the responseList is ready (all acceptors has responded) or not
    private ReentrantLock responseListStateLock; 
    private Condition responseListReady; 
    private Proposal proposal; // the proposal used for proposing if this member propose 
    private String chosenValue; // update this variable when starting to propose then the Proposer will used this value in Accept phase
    private String latencyType; // immediate, medium (respond after 5s), late(respond after 15s), never (doesn't respond) 


    // CouncilMember constructor
    // input: String (latency type: Immediate/Medium/Late/Never), ArrayList<String> (server socket info of all members in the protocol)
    // output: no 
    public CouncilMember(String latencyType, ArrayList<String> listOfAllMemberServerSocket){
        try{
            this.latencyType = latencyType; 
            this.memberId = ++ID;  
            // server socket of member listen on port 2000 + memberID
            this.memberServerSocket = new ServerSocket(STARTING_PORT + this.memberId); 
            this.lastAcceptedProposal = null; 
            this.highestProposalId = -1; 
            this.memberServerSocketList = listOfAllMemberServerSocket; 
            // calculate the majority from the size of member socket list
            this.majority = (int)Math.floor(this.memberServerSocketList.size() / 2) + 1; 
            this.lastAcceptedProposalLock = new ReentrantLock(); 
            this.highestProposalIdLock = new ReentrantLock(); 
            this.responseListLock = new ReentrantLock(); 
            this.proposal = null; 
            this.chosenValue = ""; 
            this.responseList = new ArrayList<HashMap<String, Object>>(); 

            this.responseListState = false; 
            this.responseListStateLock = new ReentrantLock(); 
            this.responseListReady = this.responseListStateLock.newCondition(); 
        
            // run the thread to run server socket of this member
            // so that this member can receive requests and act as an Acceptor 
            RunMemberServer runServer = new RunMemberServer(this); 
            Thread server = new Thread(runServer); 
            server.start(); 
            System.out.println("Server of Council Member " + this.memberId + " started."); 

        }
        catch(Exception e){
            System.out.println("Error in starting Member's Server Socket."); 
            e.printStackTrace(); 
        }
    }

    // proposer send prepare request to all the members in the protocol
    // input: no
    // output: Proposal(the new proposal with the id that is accepted by the majority)
    public Proposal sendPrepare(){
        boolean success = false; 
        // retry until success 
        while(success == false){
            this.responseListStateLock.lock(); 
            // set the response list indicating that it's not ready before send requests. 
            this.setResponseListState(false); 
            // create a new proposal with empty value
            Proposal newProposal = new Proposal(this.memberId, ""); 
            HashMap<String, Object> requestObject = new HashMap<String, Object>(); 
            // used the id of newProposal to send Prepare request 
            requestObject.put("Request-Type", "Prepare"); 
            requestObject.put("Current-Proposal-Id", newProposal.getID()); 
            requestObject.put("Sender-Id", this.memberId); 
            // create an intermediate thread to send requests to members
            IntermediateThread sendRequest = new IntermediateThread(this, requestObject); 
            Thread sendingRequest = new Thread(sendRequest); 
            sendingRequest.start(); 
            try{
                // keep waiting until receive all the responses (or timeout exceeds)
                while(this.responseListState == false){
                    responseListReady.await(); 
                }
                // after the receiving all the responses
                // keep track how many Promise received from Acceptors
                int promiseCounter = 0; 
                this.responseListLock.lock(); 
                // check the number of promise if promise >= majority or not
                for(int i = 0; i < responseList.size(); i++){
                    String response = (String)responseList.get(i).get("Status"); 
                    if(response.equals("Promise-OK")){
                        promiseCounter++; 
                    }
                }
                // if get the promise from the majority, check if any acceptor report value
                // get the reported value from the highest id
                if(promiseCounter >= this.majority){
                    // stop looping because succeeded 
                    success = true; 
                    int highestId = -1; 
                    for(int i = 0; i < responseList.size(); i++){
                        String status = (String)responseList.get(i).get("Status"); 
                        if(status.equals("Promise-OK") && responseList.get(i).containsKey("Last-Accepted-Proposal")){
                            Proposal reportedLastAcceptedProposal = (Proposal)responseList.get(i).get("Last-Accepted-Proposal"); 
                            int reportedId = (int)reportedLastAcceptedProposal.getID(); 
                            String reportedValue = reportedLastAcceptedProposal.getValue(); 
                            // get the reported value which has the highest id
                            if(reportedId > highestId){
                                highestId = reportedId; 
                                newProposal.setValue(reportedValue); 
                            }
                        }
                    }
                    return newProposal; 
                }
                else{
                    // retry again if couldn't get Promise from the majority
                    success = false; 
                }
            }
            catch(Exception e){
                System.out.println("Error when Proposer send Prepare."); 
                e.printStackTrace();
            }
            finally{
                this.responseList.clear(); 
                this.responseListLock.unlock(); 
                this.responseListStateLock.unlock(); 
            }
        }
        return null; 
    }

    // Phase 2: send Accept request to all the members
    // input: Proposal (the proposal to send in Accept request)
    // output: boolean (true if get Accept from the majority, false otherwise) 
    public boolean sendAccept(Proposal proposal){
        boolean success = false; 
        this.responseListStateLock.lock(); 
        this.setResponseListState(false); 
        // if there is no reported value in Prepare phase, 
        // proposer can choose their own value
        if(proposal.getValue().isEmpty()){
            proposal.setValue(this.chosenValue); 
        }
        // send Accept request to all acceptors 
        HashMap<String, Object> requestObject = new HashMap<String, Object>(); 
        requestObject.put("Request-Type", "Accept"); 
        requestObject.put("Current-Proposal", proposal); 
        requestObject.put("Sender-Id", this.memberId); 
        // create a thread to send requests to all members 
        IntermediateThread sendRequest = new IntermediateThread(this, requestObject); 
        Thread sendingRequest = new Thread(sendRequest); 
        sendingRequest.start(); 
        try{
            // keep waiting until get responses from members (or timeout exceeded)
            while(this.responseListState == false){
                responseListReady.await(); 
            }
            // after receiving all responses
            // count how many Accept the Proposer receive
            int acceptCounter = 0; 
            this.responseListLock.lock(); 
            // check if we get acceptance from the majority
            for(int i = 0; i < responseList.size(); i++){
                String response = (String)responseList.get(i).get("Status"); 
                if(response.equals("Accept-OK")){
                    acceptCounter++;  
                }
            }
            // if got Accept from the majority
            if(acceptCounter >= this.majority){
                // indicate the Accept phase has been successful
                success = true; 
                this.proposal = proposal; 
            }

        }
        catch(Exception e){
            System.out.println("Error happened when Proposer sending Accept request."); 
            e.printStackTrace(); 
        }
        finally{
            this.responseList.clear(); 
            this.responseListStateLock.unlock(); 
            this.responseListLock.unlock(); 
            return success; 
        }
    }

    // this function is responsible for sending Decide message to all the members
    // input: no
    // output: no 
    public void sendDecide(){
        System.out.println("Council Member " + this.memberId + " is the leader with value " + this.proposal.getValue()); 
        HashMap<String, Object> requestObject = new HashMap<String, Object>(); 
        requestObject.put("Request-Type", "Decide"); 
        requestObject.put("Current-Proposal", this.proposal); 
        IntermediateThread sendRequest = new IntermediateThread(this, requestObject); 
        Thread sendingRequest = new Thread(sendRequest); 
        sendingRequest.start(); 
        // dont need to wait for responses as Decide message doesn't require any response
    }

    // start a Paxos round
    // input: no
    // output: no 
    public void propose(){
        boolean complete = false; 
        // retry if Accept phase fails
        while(complete == false){
            Proposal newProposal = this.sendPrepare(); 
            boolean acceptPhaseCompleted = this.sendAccept(newProposal); 
            if(acceptPhaseCompleted == true){
                // send Decide message if the Accept phase succeeded 
                this.sendDecide(); 
                complete = true; 
            }
        }
    }

    // making Council Member a thread does not really make sense 
    // because by default, a Council Member is an Acceptor who will respond to 
    // any request comes. 
    // when a Council Member wants to propose, we can set the member variable chosenValue and 
    // call the propose function
    // making Council Member a thread is for testing, help us to make 2 Council Member propose at the same time
    // so the run() function here is exactly the same as propose() function 
    // having CouncilMember as a thread helps us to make 2 Proposers propose at the same time
    // this function will start a Paxos round 
    // input: no
    // output: no 
    @Override 
    public void run(){
        this.propose(); 
    }

    // used for testing, when a member server has been closed and open again 
    // member go offline then go online again
    // this function is responsible for re-run the member server (if the member server has been closed)
    // input: no
    // output: no 
    public void reRunMemberServer(){
        try{
            this.memberServerSocket = new ServerSocket(STARTING_PORT + this.memberId); 
            RunMemberServer server = new RunMemberServer(this); 
            Thread runMemberServer = new Thread(server); 
            runMemberServer.start(); 
        }
        catch(Exception e){
            System.out.println("Error when rerun Member Server."); 
            e.printStackTrace(); 
        }
    }

    // latency type getter
    // input: no
    // output: String (latency profile of this member): Immediate/Medium/Late/Never
    public String getLatencyType(){
        return this.latencyType; 
    }

    // latency type setter
    // set new latency type for this member
    // input: String (new latency type): Immediate/Medium/Late/Never
    // output: no 
    public void setLatencyType(String newLatency){
        this.latencyType = newLatency; 
    }

    // proposer chosen value for the proposal getter
    // input: no
    // output: String (value chosen for the proposal) 
    public String getChosenValue(){
        return this.chosenValue; 
    }

    // proposer chosen value for the proposal setter
    // set new value to propose
    // input: String (new chosen value)
    // output: no 
    public void setChosenValue(String newChosenValue){
        this.chosenValue = newChosenValue;  
    }

    // response list getter
    // input: no
    // output: ArrayList<String, Object> (list of response objects)
    public ArrayList<HashMap<String, Object>> getResponseList(){
        return this.responseList; 
    }

    // responseListReady getter
    // input: no
    // output: Condition
    public Condition getResponseListReady(){
        return this.responseListReady; 
    }

    // get the lock for responseListState
    // input: no
    // output: ReentrantLock (lock of resposeListState)
    public ReentrantLock getResponseListStateLock(){
        return this.responseListStateLock; 
    }

    // get the responseListState
    // input: no
    // output: boolean (true if the responseList is ready, false otherwise)
    public boolean getResponseListState(){
        return this.responseListState; 
    }

    // set the responseListState
    // input: boolean (true if the responseList is ready, false otherwise) 
    // output: no 
    public void setResponseListState(boolean newState){
        this.responseListState = newState; 
    }

    // lastAcceptedProposalLock getter
    // input: no
    // output: ReentrantLock, the lock of member field lastAcceptedProposal
    public ReentrantLock getLastAcceptedProposalLock(){
        return this.lastAcceptedProposalLock; 
    }

    // highestProposalLock getter
    // input: no
    // output: ReentrantLock, the lock of member field highestProposalId
    public ReentrantLock getHighestProposalIdLock(){
        return this.highestProposalIdLock; 
    }

    // responseListLock getter
    // input: no
    // output: ReentrantLock, the lock of member field responseList
    public ReentrantLock getResponseListLock(){
        return this.responseListLock; 
    }

    // member id getter
    // input: no 
    // output: int, this member id
    public int getMemberId(){
        return this.memberId; 
    }

    // member server socket getter
    // input: no
    // output: ServerSocket, the server socket of this member
    public ServerSocket getMemberServerSocket(){
        return this.memberServerSocket; 
    }

    // lastAcceptedProposal getter
    // input: no 
    // output: Proposal, the last accepted proposal of this member/acceptor
    public Proposal getLastAcceptedProposal(){
        return this.lastAcceptedProposal; 
    }
    
    // lastAcceptedProposal setter
    // input: Proposal, new accepted proposal
    // output: no
    public void setLastAcceptedProposal(Proposal newAcceptedProposal){
        this.lastAcceptedProposal = newAcceptedProposal; 
    }

    // highestProposalId getter
    // input: no
    // output: int, the highest proposal this member/acceptor has seen so far
    public int getHighestProposalId(){
        return this.highestProposalId; 
    }

    // highestProposalId setter
    // input: int, the new highest proposal id
    // output: no 
    public void setHighestProposalId(int newHighestProposalId){
        this.highestProposalId = newHighestProposalId; 
    }

    // memberServerSocketList getter
    // input: no
    // output: ArrayList<String>, the members' server socket information list
    public ArrayList<String> getMemberServerSocketList(){
        return this.memberServerSocketList; 
    }

    // shut down this member server 
    // so that this member will not get any request from Proposers. 
    // input: no
    // output: no
    public void shutDownServer(){
        try{
            this.memberServerSocket.close(); 
        }
        catch(Exception e){
            e.printStackTrace(); 
        }
    }

    // produce the output for a test case and write the output to output file
    // input: ArrayList<CouncilMember> (the list of members in the test case), String (the file location to write output to) 
    // output: no 
    // after a test case, go through all the members in the protocol
    // check their accepted value
    // the value which the majority agreed on is the final output of the test case 
    public static void writeOutputToTestFile(ArrayList<CouncilMember> memberList, String outputFileLocation){
        HashMap<String, Integer> acceptedValueCounterTable = new HashMap<String, Integer>(); 
        // collect the accepted values from all the members in the test case
        for(int i = 0; i < memberList.size(); i++){
            Object acceptedProposal = memberList.get(i).getLastAcceptedProposal(); 
            String acceptedValue = ""; 
            // some Acceptor get killed before Accept any Proposal
            // so their lastAcceptedProposal will be null
            // need to check null
            if(acceptedProposal != null){
                acceptedValue = memberList.get(i).getLastAcceptedProposal().getValue(); 
            }
            if(acceptedValueCounterTable.containsKey(acceptedValue)){
                int currentCounter = acceptedValueCounterTable.get(acceptedValue); 
                acceptedValueCounterTable.remove(acceptedValue); 
                acceptedValueCounterTable.put(acceptedValue, currentCounter+1); 
            }
            else{
                acceptedValueCounterTable.put(acceptedValue, 0); 
            }
        }
        int highestCounter = 0; 
        String valueWithHighestCounter = ""; 
        // go through the counter table to get the value that being agreed by the majority
        for(Map.Entry<String, Integer> entry : acceptedValueCounterTable.entrySet()){
            String value = entry.getKey(); 
            int counter = entry.getValue(); 
            if(counter > highestCounter){
                highestCounter = counter; 
                valueWithHighestCounter = value; 
            }
        }
        // write the output to output file
        try{
            FileWriter fileWriter = new FileWriter(outputFileLocation); 
            PrintWriter printWriter = new PrintWriter(fileWriter); 
            printWriter.print(valueWithHighestCounter); 
            printWriter.close(); 
        }
        catch(Exception e){

        }
    }
    
    public static void main(String args[]){
        // normal mode
        if(args[0].equals("normal")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 9; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            CouncilMember memberTwo = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberTwo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberThree); 
            CouncilMember memberFour = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberFour); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberFive); 
            CouncilMember memberSix = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberSix); 
            CouncilMember memberSeven = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberSeven); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberEight); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberNine); 
            memberOne.setChosenValue("13"); 
            memberOne.propose(); 
        }
        // test case 1: 1 Proposer -> 9 immediate members in the system 
        if(args[0].equals("testing") && args[1].equals("1")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 9; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            CouncilMember memberTwo = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberTwo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberThree); 
            CouncilMember memberFour = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberFour); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberFive); 
            CouncilMember memberSix = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberSix); 
            CouncilMember memberSeven = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberSeven); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberEight); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberNine); 
            memberOne.setChosenValue("13"); 
            memberOne.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase1Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 9; i++){
                memberList.get(i).shutDownServer(); 
            }

        }
        // test case 2: 1 Proposer -> 9 members (different latency profile) in the protocol
        if(args[0].equals("testing") && args[1].equals("2")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 9; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberTwo); 
            CouncilMember memberThree = new CouncilMember("Late", serverSocketInfo); 
            memberList.add(memberThree); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            memberList.add(memberFour); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberFive); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberSix); 
            CouncilMember memberSeven = new CouncilMember("Never", serverSocketInfo); 
            memberList.add(memberSeven); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberEight); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberNine); 
            memberOne.setChosenValue("08"); 
            memberOne.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase2Output.txt");
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 9; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 3: 1 Proposer -> 'n' members (13 members in this test case), member has different latency profiles
        if(args[0].equals("testing") && args[1].equals("3")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 13; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberTwo); 
            CouncilMember memberThree = new CouncilMember("Late", serverSocketInfo); 
            memberList.add(memberThree); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            memberList.add(memberFour); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberFive); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberSix); 
            CouncilMember memberSeven = new CouncilMember("Never", serverSocketInfo); 
            memberList.add(memberSeven); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberEight); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberNine); 
            CouncilMember memberTen = new CouncilMember("Never", serverSocketInfo); 
            memberList.add(memberTen); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberEleven); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            memberList.add(memberTwelve); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberThirteen); 
            memberTwo.setChosenValue("11"); 
            memberTwo.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase3Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 13; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 4: 1 Proposer -> 'n' members (8 members in this test case), all members has 'Immediate' latency profiles
        if(args[0].equals("testing") && args[1].equals("4")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 8; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 

            memberEight.setChosenValue("2001"); 
            memberEight.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase4Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 8; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 5: 2 Proposer (propose at different time, propose different values) -> 9 members (all member has 'Immediate' profile)
        if(args[0].equals("testing") && args[1].equals("5")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 9; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            // member one propose first
            memberOne.setChosenValue("2001"); 
            memberOne.propose(); 
            // then member two propose 
            memberTwo.setChosenValue("1980"); 
            memberTwo.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase5Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 9; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 6: 3 Proposers (propose at different time, propose different values) -> 15 members (members has different latency profiles) 
        if(args[0].equals("testing") && args[1].equals("6")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 15; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFourteen = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberFifteen = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            memberList.add(memberFourteen); 
            memberList.add(memberFifteen); 
            // member 1 proposes first
            memberOne.setChosenValue("0808"); 
            memberOne.propose(); 
            // then member 2 proposes
            memberTwo.setChosenValue("0808"); 
            memberTwo.propose(); 
            // then member 3 proposes 
            memberThree.setChosenValue("1311"); 
            memberThree.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase6Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 15; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 7: 2 Proposers (propose at the same time, propose different values) -> 9 members (members has the same profile 'medium')
        if(args[0].equals("testing") && args[1].equals("7")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 9; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            // member one and member two propose at the same time
            memberOne.setChosenValue("2001"); 
            memberTwo.setChosenValue("1980"); 
            Thread memberOnePropose = new Thread(memberOne); 
            Thread memberTwoPropose = new Thread(memberTwo); 
            memberOnePropose.start(); 
            memberTwoPropose.start(); 
            try{
                memberOnePropose.join(); 
                memberTwoPropose.join(); 
            }
            catch(Exception e){

            }
            writeOutputToTestFile(memberList, "Testing/TestCase7Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 9; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 8: 3 Proposers (propose at the same time, propose different values) -> 13 members (member has different latency profiles)
        if(args[0].equals("testing") && args[1].equals("8")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 13; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            // 3 proposer propose at the same time
            memberOne.setChosenValue("1999"); 
            memberTwo.setChosenValue("1980"); 
            memberThree.setChosenValue("1979"); 
            Thread memberOnePropose = new Thread(memberOne); 
            Thread memberTwoPropose = new Thread(memberTwo); 
            Thread memberThreePropose = new Thread(memberThree); 
            memberOnePropose.start(); 
            memberTwoPropose.start(); 
            memberThreePropose.start(); 
            try{
                memberOnePropose.join(); 
                memberTwoPropose.join(); 
                memberThreePropose.join(); 
            }
            catch(Exception e){

            }
            writeOutputToTestFile(memberList, "Testing/TestCase8Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 13; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 9: 1 Proposer send Prepare without Accept and Decide following up
        // another Proposer will start proposing after that
        if(args[0].equals("testing") && args[1].equals("9")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 13; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            memberOne.sendPrepare(); 
            memberTwo.setChosenValue("1311"); 
            memberTwo.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase9Output.txt"); 
            
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 13; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 10: 1 Proposer propose then go offline 
        // then another 2 Proposers will propose at the same time, different value
        if(args[0].equals("testing") && args[1].equals("10")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 15; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFourteen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberFifteen = new CouncilMember("Never", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            memberList.add(memberFourteen); 
            memberList.add(memberFifteen); 
            memberOne.setChosenValue("2003"); 
            memberOne.propose(); 
            memberOne.shutDownServer(); 
            memberTwo.setChosenValue("1999"); 
            memberThree.setChosenValue("1980"); 
            Thread memberTwoPropose = new Thread(memberTwo); 
            Thread memberThreePropose = new Thread(memberThree); 
            memberTwoPropose.start(); 
            memberThreePropose.start(); 
            try{
                memberTwoPropose.join(); 
                memberThreePropose.join(); 
            }
            catch(Exception e){

            }
            writeOutputToTestFile(memberList, "Testing/TestCase10Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            // iteration start from i=1, because memberList[0] = memberOne has closed its server already 
            for(int i = 1; i < 15; i++){
                memberList.get(i).shutDownServer(); 
            }
        }
        // test case 11: 2 Proposers propose at the same time (different value)
        // in the middle of the Paxos round, some Acceptors go offline
        // after 2 Proposers proposed, they go offline, then another Proposer will propose
        if(args[0].equals("testing") && args[1].equals("11")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 15; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFourteen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberFifteen = new CouncilMember("Medium", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            memberList.add(memberFourteen); 
            memberList.add(memberFifteen); 
            memberOne.setChosenValue("2001"); 
            memberTwo.setChosenValue("2003"); 
            Thread memberOnePropose = new Thread(memberOne); 
            Thread memberTwoPropose = new Thread(memberTwo); 
            memberOnePropose.start(); 
            memberTwoPropose.start(); 
            // kill some Acceptors in the middle of the Paxos
            try{
                Thread.sleep(2000); // delay 2s for the Paxos run
            }
            catch(Exception e){

            }
            memberNine.shutDownServer(); 
            memberTen.shutDownServer(); 
            System.out.println("Kill Member 9 and Member 10."); 
            // wait for the first 2 Proposers complete
            try{
                memberOnePropose.join(); 
                memberTwoPropose.join(); 
            }
            catch(Exception e){

            }
            // then another Proposer propose
            memberFour.setChosenValue("1980"); 
            memberFour.propose(); 
            writeOutputToTestFile(memberList, "Testing/TestCase11Output.txt"); 
            // sleep 2s to make sure all the printings are completed
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down server of all members in this test case
            // so that the next test case can be run
            for(int i = 0; i < 15; i++){
                // memberNine = memberList[8] 
                // memberTen = memberList[9] 
                // have closed their server already
                if(i != 8 && i != 9){
                    memberList.get(i).shutDownServer(); 
                }
            }
        }
        // test case 12: 3 Proposers propose (at the same time, different value) -> 'n' Acceptors
        // in the middle of the Paxos round, some Acceptors go offline
        // then back to online again
        if(args[0].equals("testing") && args[1].equals("12")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 20; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFourteen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberFifteen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSixteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSeventeen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberEighteen = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberNineteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwenty = new CouncilMember("Late", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            memberList.add(memberFourteen); 
            memberList.add(memberFifteen); 
            memberList.add(memberSixteen); 
            memberList.add(memberSeventeen); 
            memberList.add(memberEighteen); 
            memberList.add(memberNineteen); 
            memberList.add(memberTwenty); 
            // 3 Proposers propose at the same time
            memberFour.setChosenValue("1980"); 
            memberFive.setChosenValue("1999"); 
            memberSix.setChosenValue("2001"); 
            Thread memberFourPropose = new Thread(memberFour); 
            Thread memberFivePropose = new Thread(memberFive); 
            Thread memberSixPropose = new Thread(memberSix); 
            memberFourPropose.start(); 
            memberFivePropose.start(); 
            memberSixPropose.start(); 
            // some Acceptors go offline in the middle of the Paxos round
            try{
                Thread.sleep(3000); 
            }
            catch(Exception e){

            }
            memberSixteen.shutDownServer(); 
            memberOne.shutDownServer(); 
            memberNine.shutDownServer(); 
            System.out.println("Kill Member 16, Member 1 and Member 9."); 
            // go back online in the middle of the Paxos round
            try{
                Thread.sleep(4000); 
            }
            catch(Exception e){

            }
            memberSixteen.reRunMemberServer(); 
            memberOne.reRunMemberServer(); 
            memberNine.reRunMemberServer(); 
            System.out.println("Member 16, Member 1, Member 9 go back online."); 
            try{
                memberFourPropose.join(); 
                memberFivePropose.join(); 
                memberSixPropose.join(); 
            }
            catch(Exception e){

            }
            writeOutputToTestFile(memberList, "Testing/TestCase12Output.txt"); 
            // wait for 2 seconds to make sure all the printing information has been completed 
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down all the server of members from this test case
            // so that the next test case can be run
            for(int i = 0; i < memberList.size(); i++){
                 memberList.get(i).shutDownServer(); 
            }
        }
        // test case 13: 3 Proposers propose at the same time, different values
        // in the middle, kill all the members in the protocol (including 3 Proposers)
        // so that all the members will not get any request from Proposers
        // then go back online
        if(args[0].equals("testing") && args[1].equals("13")){
            ArrayList<String> serverSocketInfo = new ArrayList<String>(); 
            ArrayList<CouncilMember> memberList = new ArrayList<CouncilMember>(); 
            for(int i = 1; i <= 20; i++){
                String hostname = "localhost"; 
                String port = Integer.toString(STARTING_PORT + i); 
                String socketInfo = hostname + ":" + port; 
                serverSocketInfo.add(socketInfo); 
            }
            CouncilMember memberOne = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwo = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberThree = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFour = new CouncilMember("Never", serverSocketInfo); 
            CouncilMember memberFive = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSix = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSeven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberEight = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberNine = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberEleven = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberTwelve = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberThirteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberFourteen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberFifteen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberSixteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberSeventeen = new CouncilMember("Medium", serverSocketInfo); 
            CouncilMember memberEighteen = new CouncilMember("Late", serverSocketInfo); 
            CouncilMember memberNineteen = new CouncilMember("Immediate", serverSocketInfo); 
            CouncilMember memberTwenty = new CouncilMember("Late", serverSocketInfo); 
            memberList.add(memberOne); 
            memberList.add(memberTwo); 
            memberList.add(memberThree); 
            memberList.add(memberFour); 
            memberList.add(memberFive); 
            memberList.add(memberSix); 
            memberList.add(memberSeven); 
            memberList.add(memberEight); 
            memberList.add(memberNine); 
            memberList.add(memberTen); 
            memberList.add(memberEleven); 
            memberList.add(memberTwelve); 
            memberList.add(memberThirteen); 
            memberList.add(memberFourteen); 
            memberList.add(memberFifteen); 
            memberList.add(memberSixteen); 
            memberList.add(memberSeventeen); 
            memberList.add(memberEighteen); 
            memberList.add(memberNineteen); 
            memberList.add(memberTwenty); 
            // 3 Proposers propose at the same time
            memberFour.setChosenValue("1980"); 
            memberFive.setChosenValue("1999"); 
            memberSix.setChosenValue("2001"); 
            Thread memberFourPropose = new Thread(memberFour); 
            Thread memberFivePropose = new Thread(memberFive); 
            Thread memberSixPropose = new Thread(memberSix); 
            memberFourPropose.start(); 
            memberFivePropose.start(); 
            memberSixPropose.start(); 
            // all Acceptors go offline in the middle of the Paxos round
            try{
                Thread.sleep(4000); 
            }
            catch(Exception e){

            }
            for(int i = 0; i < memberList.size(); i++){
                memberList.get(i).shutDownServer(); 
            }
            System.out.println("Kill all members in the protocol."); 
            // go back online after 7 seconds
            try{
                Thread.sleep(7000); 
            }
            catch(Exception e){

            }
            for(int i = 0; i < memberList.size(); i++){
                memberList.get(i).reRunMemberServer(); 
            }
            System.out.println("All the members in the protocol go back online."); 
            try{
                memberFourPropose.join(); 
                memberFivePropose.join(); 
                memberSixPropose.join(); 
            }
            catch(Exception e){

            }
            writeOutputToTestFile(memberList, "Testing/TestCase13Output.txt"); 
            // wait for 2 seconds to make sure all the printing information has been completed 
            try{
                Thread.sleep(2000); 
            }
            catch(Exception e){

            }
            // shut down all the server of members from this test case
            // so that the next test case can be run
            for(int i = 0; i < memberList.size(); i++){
                 memberList.get(i).shutDownServer(); 
            }
        }
    }
}
