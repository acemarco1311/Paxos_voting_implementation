import java.io.*; 
public class Proposal implements java.io.Serializable{
    private static int proposalID = 0; 
    private int memberID; // the id of the Proposer who creates this Proposal
    private String value; // value of the Proposal
    private int id; // id of the proposal

    // Proposal constructor
    // input: int (id of proposer who creates this proposal), String (value of this proposal)
    // output: no
    public Proposal(int memberID, String value){
        this.memberID = memberID; 
        this.value = value; 
        this.id = ++proposalID; 
    }
    
    // memberId getter
    // input: no
    // output: int (the id of the member who created this proposal)
    public int getMemberID(){
        return this.memberID; 
    }
    
    // get the value of this Proposal
    // input: no
    // output: String
    public String getValue(){
        return this.value; 
    }

    // get the id of this proposal
    // input: no
    // output: int
    public int getID(){
        return this.id; 
    }

    // set the value of this proposal
    // input: String (new value for this proposal)
    // output: no
    public void setValue(String value){
        this.value = value; 
    }

    // set the id of this proposal
    // input: int (new id for this proposal)
    // output: no
    public void setID(int id){
        this.id = id; 
    }
}
