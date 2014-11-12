package unluac.decompile;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import unluac.decompile.block.Block;
import unluac.decompile.block.NewElseEndBlock;
import unluac.decompile.block.NewIfThenElseBlock;
import unluac.decompile.block.NewIfThenEndBlock;
import unluac.decompile.block.NewRepeatBlock;
import unluac.decompile.block.NewSetBlock;
import unluac.decompile.block.NewWhileBlock;
import unluac.decompile.block.OuterBlock;
import unluac.decompile.block.TForBlock;
import unluac.decompile.condition.AndCondition;
import unluac.decompile.condition.BinaryCondition;
import unluac.decompile.condition.Condition;
import unluac.decompile.condition.OrCondition;
import unluac.decompile.condition.SetCondition;
import unluac.decompile.condition.TestCondition;
import unluac.parse.LFunction;

public class ControlFlowHandler {
  
  private static class Branch implements Comparable<Branch> {
    
    private static enum Type {
      comparison,
      //comparisonset,
      test,
      testset,
      finalset,
      jump;
    }
    
    public Branch previous;
    public Branch next;
    public int line;
    public int target;
    public Type type;
    public Condition cond;
    public int targetFirst;
    public int targetSecond;
    public boolean inverseValue;
    
    public Branch(int line, Type type, Condition cond, int targetFirst, int targetSecond) {
      this.line = line;
      this.type = type;
      this.cond = cond;
      this.targetFirst = targetFirst;
      this.targetSecond = targetSecond;
      this.inverseValue = false;
    }

    @Override
    public int compareTo(Branch other) {
      return this.line - other.line;
    }
  }
  
  private static class State {
    public LFunction function;
    public Registers r;
    public Code code;
    public Branch begin_branch;
    public Branch end_branch;
    public Branch[] branches;
    public boolean[] reverse_targets;
    public List<Block> blocks;
  }
  
  public static List<Block> process(Decompiler d, Registers r) {
    State state = new State();
    state.function = d.function;
    state.r = r;
    state.code = d.code;
    find_reverse_targets(state);
    find_branches(state);
    combine_branches(state);
    unredirect_branches(state);
    initialize_blocks(state);
    find_fixed_blocks(state);
    find_blocks(state);
    // DEBUG: print branches stuff
    /*
    Branch b = state.begin_branch;
    while(b != null) {
      System.out.println("Branch at " + b.line);
      System.out.println("\tcondition: " + b.cond);
      b = b.next;
    }
    */
    return state.blocks;
  }
  
  private static void find_reverse_targets(State state) {
    Code code = state.code;
    boolean[] reverse_targets = state.reverse_targets = new boolean[state.code.length];
    for(int line = 1; line <= code.length; line++) {
      if(code.op(line) == Op.JMP) {
        int target = code.target(line);
        if(target <= line) {
          reverse_targets[target] = true;
        }
      }
    }
  }
  
  private static void find_branches(State state) {
    Code code = state.code;
    state.branches = new Branch[state.code.length + 1];
    boolean[] skip = new boolean[code.length + 1];
    for(int line = 1; line <= code.length; line++) {
      if(!skip[line]) {
        switch(code.op(line)) {
          case EQ:
          case LT:
          case LE: {
            BinaryCondition.Operator op = BinaryCondition.Operator.EQ;
            if(code.op(line) == Op.LT) op = BinaryCondition.Operator.LT;
            if(code.op(line) == Op.LE) op = BinaryCondition.Operator.LE;
            int left = code.B(line);
            int right = code.C(line);
            Condition c = new BinaryCondition(op, line, left, right);
            if(code.A(line) == 1) {
              c = c.inverse();
            }
            Branch b = new Branch(line, Branch.Type.comparison, c, line + 2, code.target(line + 1));
            skip[line + 1] = true;
            insert_branch(state, b);
            break;
          }
          case TEST: {
            Condition c = new TestCondition(line, code.A(line));
            if(code.C(line) != 0) c = c.inverse();
            Branch b = new Branch(line, Branch.Type.test, c, line + 2, code.target(line + 1));
            if(code.C(line) != 0) b.inverseValue = true;
            skip[line + 1] = true;
            insert_branch(state, b);
            break;
          }
          case TESTSET: {
            Condition c = new TestCondition(line, code.B(line));
            int target = code.target(line + 1);
            Branch b = new Branch(line, Branch.Type.testset, c, line + 2, target);
            b.target = code.A(line);
            if(code.C(line) != 0) b.inverseValue = true;
            skip[line + 1] = true;
            insert_branch(state, b);
            int final_line = target - 1;
            if(state.branches[final_line] == null) {
              c = new SetCondition(final_line, get_target(state, final_line));
              b = new Branch(final_line, Branch.Type.finalset, c, target, target);
              insert_branch(state, b);
            }
            break;
          }
          case JMP: {
            int target = code.target(line);
            Branch b = new Branch(line, Branch.Type.jump, null, target, target);
            insert_branch(state, b);
            break;
          }
        }
      }
    }
    link_branches(state);
  }
  
  private static void combine_branches(State state) {
    Branch b;
    
    b = state.end_branch;
    while(b != null) {
      b = combine_left(state, b).previous;
    }
    /*
    b = state.end_branch;
    while(b != null) {
      Branch result = combine_right(state, b);
      if(result != null) {
        b = result;
        if(b.next != null) {
          b = b.next;
        }
      } else {
        b = b.previous;
      }
    }
    */
  }
  
  private static void unredirect_branches(State state) {
    // There is more complication here
    int[] redirect = new int[state.code.length + 1];
    
    Branch b = state.end_branch;
    while(b != null) {
      if(b.type == Branch.Type.jump) {
        if(redirect[b.targetFirst] == 0) {
          redirect[b.targetFirst] = b.line;
        } else {
          int temp = b.targetFirst;
          b.targetFirst = b.targetSecond = redirect[temp];
          redirect[temp] = b.line;
        }
      }
      b = b.previous;
    }
    b = state.begin_branch;
    while(b != null) {
      if(b.type != Branch.Type.jump) {
        if(redirect[b.targetSecond] != 0) {
          // Hack-ish -- redirect can't extend the scope
          boolean skip = false;
          if(b.targetSecond > b.line & redirect[b.targetSecond] > b.targetSecond) skip = true;
          if(!skip) {
            //System.out.println("Redirected to " + redirct[b.targetSecond] + " from " + b.targetSecond);
            //if(redirect[b.targetSecond] < b.targetSecond)
            b.targetSecond = redirect[b.targetSecond];
          }
        }
      }
      b = b.next;
    }
  }
  
  private static void initialize_blocks(State state) {
    state.blocks = new LinkedList<Block>();
  }
  
  private static void find_fixed_blocks(State state) {
    List<Block> blocks = state.blocks;
    Registers r = state.r;
    Code code = state.code;
    Op tforTarget = state.function.header.version.getTForTarget();
    blocks.add(new OuterBlock(state.function, state.code.length));
    for(int line = 1; line <= code.length; line++) {
      switch(code.op(line)) {
        case JMP:
          int target = code.target(line); 
          if(code.op(target) == tforTarget) {
            int A = code.A(target);
            int C = code.C(target);
            if(C == 0) throw new IllegalStateException();
            r.setInternalLoopVariable(A, target, line + 1); //TODO: end?
            r.setInternalLoopVariable(A + 1, target, line + 1);
            r.setInternalLoopVariable(A + 2, target, line + 1);
            for(int index = 1; index <= C; index++) {
              r.setExplicitLoopVariable(A + 2 + index, line, target + 2); //TODO: end?
            }
            blocks.add(new TForBlock(state.function, line + 1, target + 2, A, C, r));
          }
          break;
        case FORPREP:
          break;
      }
    }
  }
  
  private static void find_blocks(State state) {
    List<Block> blocks = state.blocks;
    Code code = state.code;
    Branch b = state.begin_branch;
    while(b != null) {
      if(is_conditional(b)) {
        // Conditional branches decompile to if, while, or repeat
        boolean has_tail = false;
        int tail_line = b.targetSecond - 1;
        int tail_target = 0;
        if(tail_line >= 1 && code.op(tail_line) == Op.JMP) {
          Branch tail_branch = state.branches[tail_line];
          if(tail_branch != null && tail_branch.type == Branch.Type.jump) {
            has_tail = true;
            tail_target = tail_branch.targetFirst;
          }
        }
        
        if(b.targetSecond > b.targetFirst) {
          if(has_tail) {
            if(tail_target > tail_line) {
              // If -- then -- else
              //System.out.println("If -- then -- else");
              //System.out.println("\t" + b.line + "\t" + b.cond.toString());
              NewIfThenElseBlock block = new NewIfThenElseBlock(state.function, state.r, b.cond, b.targetFirst, b.targetSecond);
              NewElseEndBlock block2 = new NewElseEndBlock(state.function, b.targetSecond, tail_target);
              block.partner = block2;
              block2.partner = block;
              //System.out.println("else -- end " + block2.begin + " " + block2.end);
              blocks.add(block);
              blocks.add(block2);
            } else {
              if(tail_target <= b.line) {
                // While
                //System.out.println("While");
                //System.out.println("\t" + b.line + "\t" + b.cond.toString());
                Block block = new NewWhileBlock(state.function, state.r, b.cond, b.targetFirst, b.targetSecond);
                blocks.add(block);
              } else {
                // If -- then (tail is from an inner loop)
                //System.out.println("If -- then");
                //System.out.println("\t" + b.line + "\t" + b.cond.toString());
                Block block = new NewIfThenEndBlock(state.function, state.r, b.cond, b.targetFirst, b.targetSecond);
                blocks.add(block);
              }
            }
          } else {
            // If -- then
            //System.out.println("If -- then");
            //System.out.println("\t" + b.line + "\t" + b.cond.toString() + "\t" + b.targetFirst + "\t" + b.targetSecond);
            Block block = new NewIfThenEndBlock(state.function, state.r, b.cond, b.targetFirst, b.targetSecond);
            blocks.add(block);
          }
        } else {
          // Repeat
          //System.out.println("Repeat " + b.targetSecond + " .. " + b.targetFirst);
          //System.out.println("\t" + b.line + "\t" + b.cond.toString());
          Block block = new NewRepeatBlock(state.function, state.r, b.cond, b.targetSecond, b.targetFirst);
          blocks.add(block);
        }
      } else if(is_assignment(b) || b.type == Branch.Type.finalset) {
        Block block = new NewSetBlock(state.function, b.cond, b.target, b.line, b.targetFirst, b.targetSecond, false, state.r);
        blocks.add(block);
        //System.out.println("Assign block " + b.line);
      }
      b = b.next;
    }
    Collections.sort(blocks);
  }
  
  private static boolean is_conditional(Branch b) {
    return b.type == Branch.Type.comparison || b.type == Branch.Type.test;
  }
  
  private static boolean is_assignment(Branch b) {
    return b.type == Branch.Type.testset;
  }
  
  private static boolean adjacent(State state, Branch branch0, Branch branch1) {
    if(branch0 == null || branch1 == null) {
      return false;
    } else {
      boolean adjacent = branch0.targetFirst <= branch1.line;
      if(adjacent) {
        for(int line = branch0.targetFirst; line < branch1.line; line++) {
          if(is_statement(state, line)) {
            System.out.println("Found statement at " + line + " between " + branch0.line + " and " + branch1.line);
            adjacent = false;
            break;
          }
        }
      }
      return adjacent;
    }
  }
  
  private static Branch combine_left(State state, Branch branch1) {
    if(is_conditional(branch1)) {
      return combine_conditional(state, branch1);
    } else {
      return combine_assignment(state, branch1);
    }
  }
  
  private static Branch combine_conditional(State state, Branch branch1) {
    Branch branch0 = branch1.previous;
    if(adjacent(state, branch0, branch1) && is_conditional(branch0) && is_conditional(branch1)) {
      if(branch0.targetSecond == branch1.targetFirst) {
        // Combination if not branch0 or branch1 then
        branch0 = combine_conditional(state, branch0);
        Condition c = new OrCondition(branch0.cond.inverse(), branch1.cond);
        Branch branchn = new Branch(branch0.line, Branch.Type.comparison, c, branch1.targetFirst, branch1.targetSecond);
        System.err.println("conditional or " + branchn.line);
        replace_branch(state, branch0, branch1, branchn);
        return combine_conditional(state, branchn);
      } else if(branch0.targetSecond == branch1.targetSecond) {
        // Combination if branch0 and branch1 then
        branch0 = combine_conditional(state, branch0);
        Condition c = new AndCondition(branch0.cond, branch1.cond);
        Branch branchn = new Branch(branch0.line, Branch.Type.comparison, c, branch1.targetFirst, branch1.targetSecond);
        System.err.println("conditional and " + branchn.line);
        replace_branch(state, branch0, branch1, branchn);
        return combine_conditional(state, branchn);
      }
    }
    return branch1;
  }
  
  private static Branch combine_assignment(State state, Branch branch1) {
    Branch branch0 = branch1.previous;
    if(adjacent(state, branch0, branch1)) {
      System.err.println("blah " + branch0.line);
      if(is_conditional(branch0) && is_assignment(branch1)) {
        System.err.println("bridge cand " + branch0.line);
        if(branch0.targetSecond == branch1.targetFirst) {
          branch0 = combine_conditional(state, branch0);
          Condition c;
          if(branch0.inverseValue) {
            System.err.println("bridge or " + branch0.line + " " + branch0.inverseValue);
            c = new OrCondition(branch0.cond.inverse(), branch1.cond); 
          } else {
            System.err.println("bridge and " + branch0.line + " " + branch0.inverseValue);
            c = new AndCondition(branch0.cond, branch1.cond);
          }
          Branch branchn = new Branch(branch0.line, branch1.type, c, branch1.targetFirst, branch1.targetSecond);
          branchn.inverseValue = branch1.inverseValue;
          replace_branch(state, branch0, branch1, branchn);
          return branchn;
        } else if(branch0.targetSecond == branch1.targetSecond) {
          /*
          Condition c = new AndCondition(branch0.cond, branch1.cond);
          Branch branchn = new Branch(branch0.line, Branch.Type.comparison, c, branch1.targetFirst, branch1.targetSecond);
          replace_branch(state, branch0, branch1, branchn);
          return branchn;
          */
        }
      }
      
      if(is_assignment(branch0) && is_assignment(branch1) && branch0.inverseValue == branch1.inverseValue) {
        if(branch0.targetSecond == branch1.targetSecond) {
          Condition c;
          branch0 = combine_assignment(state, branch0);
          if(branch0.inverseValue) {
            System.err.println("assign and " + branch0.line);
            c = new OrCondition(branch0.cond, branch1.cond);
          } else {
            System.err.println("assign or " + branch0.line);
            c = new AndCondition(branch0.cond, branch1.cond);
          }
          Branch branchn = new Branch(branch0.line, branch1.type, c, branch1.targetFirst, branch1.targetSecond);
          branchn.inverseValue = branch1.inverseValue;
          return combine_assignment(state, branchn);
        }
      }
      if(is_assignment(branch0) && branch1.type == Branch.Type.finalset) {
        if(branch0.targetSecond == branch1.targetFirst) {
          Condition c;
          branch0 = combine_assignment(state, branch0);
          if(branch0.inverseValue) {
            System.err.println("final assign and " + branch0.line);
            c = new OrCondition(branch0.cond, branch1.cond);
          } else {
            System.err.println("final assign or " + branch0.line);
            c = new AndCondition(branch0.cond, branch1.cond);
          }
          Branch branchn = new Branch(branch0.line, Branch.Type.finalset, c, branch1.targetFirst, branch1.targetFirst);
          replace_branch(state, branch0, branch1, branchn);
          return combine_assignment(state, branchn);
        }
      }
    }
    return branch1;
  }
  
  private static void replace_branch(State state, Branch branch0, Branch branch1, Branch branchn) {
    state.branches[branch0.line] = null;
    state.branches[branch1.line] = null;
    branchn.previous = branch0.previous;
    if(branchn.previous == null) {
      state.begin_branch = branchn;
    } else {
      branchn.previous.next = branchn;
    }
    branchn.next = branch1.next;
    if(branchn.next == null) {
      state.end_branch = branchn;
    } else {
      branchn.next.previous = branchn;
    }
    state.branches[branchn.line] = branchn;
  }
  
  private static void insert_branch(State state, Branch b) {
    state.branches[b.line] = b;
  }
  
  private static void link_branches(State state) {
    Branch previous = null;
    for(int index = 0; index < state.branches.length; index++) {
      Branch b = state.branches[index];
      if(b != null) {
        b.previous = previous;
        if(previous != null) {
          previous.next = b;
        } else {
          state.begin_branch = b;
        }
        previous = b;
      }
    }
    state.end_branch = previous;
  }
  
  /**
   * Returns the target register of the instruction at the given
   * line or -1 if the instruction does not have a unique target.
   * 
   * TODO: this probably needs a more careful pass
   */
  private static int get_target(State state, int line) {
    Code code = state.code;
    switch(code.op(line)) {
      case MOVE:
      case LOADK:
      case LOADBOOL:
      case GETUPVAL:
      case GETTABUP:
      case GETGLOBAL:
      case GETTABLE:
      case NEWTABLE:
      case ADD:
      case SUB:
      case MUL:
      case DIV:
      case MOD:
      case POW:
      case UNM:
      case NOT:
      case LEN:
      case CONCAT:
      case CLOSURE:
        return code.A(line);
      case LOADNIL:
        if(code.A(line) == code.B(line)) {
          return code.A(line);
        } else {
          return -1;
        }
      case SETGLOBAL:
      case SETUPVAL:
      case SETTABUP:
      case SETTABLE:
      case JMP:
      case TAILCALL:
      case RETURN:
      case FORLOOP:
      case FORPREP:
      case TFORCALL:
      case TFORLOOP:
      case CLOSE:
        return -1;
      case SELF:
        return -1;
      case EQ:
      case LT:
      case LE:
      case TEST:
      case TESTSET:
      case SETLIST:
        return -1;
      case CALL: {
        int a = code.A(line);
        int c = code.C(line);
        if(c == 2) {
          return a;
        } else {
          return -1; 
        }
      }
      case VARARG: {
        int a = code.A(line);
        int b = code.B(line);
        if(b == 1) {
          return a;
        } else {
          return -1;
        }
      }
      default:
        throw new IllegalStateException();
    }
  }
  
  private static boolean is_statement(State state, int line) {
    if(state.reverse_targets[line]) return true;
    Registers r = state.r;
    int testRegister = -1;
    Code code = state.code;
    switch(code.op(line)) {
      case MOVE:
      case LOADK:
      case LOADBOOL:
      case GETUPVAL:
      case GETTABUP:
      case GETGLOBAL:
      case GETTABLE:
      case NEWTABLE:
      case ADD:
      case SUB:
      case MUL:
      case DIV:
      case MOD:
      case POW:
      case UNM:
      case NOT:
      case LEN:
      case CONCAT:
      case CLOSURE:
        return r.isLocal(code.A(line), line) || code.A(line) == testRegister;
      case LOADNIL:
        for(int register = code.A(line); register <= code.B(line); register++) {
          if(r.isLocal(register, line)) {
            return true;
          }
        }
        return false;
      case SETGLOBAL:
      case SETUPVAL:
      case SETTABUP:
      case SETTABLE:
      case JMP:
      case TAILCALL:
      case RETURN:
      case FORLOOP:
      case FORPREP:
      case TFORCALL:
      case TFORLOOP:
      case CLOSE:
        return true;
      case SELF:
        return r.isLocal(code.A(line), line) || r.isLocal(code.A(line) + 1, line);
      case EQ:
      case LT:
      case LE:
      case TEST:
      case TESTSET:
      case SETLIST:
        return false;
      case CALL: {
        int a = code.A(line);
        int c = code.C(line);
        if(c == 1) {
          return true;
        }
        if(c == 0) c = r.registers - a + 1;
        for(int register = a; register < a + c - 1; register++) {
          if(r.isLocal(register, line)) {
            return true;
          }
        }
        return (c == 2 && a == testRegister);
      }
      case VARARG: {
        int a = code.A(line);
        int b = code.B(line);
        if(b == 0) b = r.registers - a + 1;
        for(int register = a; register < a + b - 1; register++) {
          if(r.isLocal(register, line)) {
            return true;
          }
        }
        return false;
      }
      default:
        throw new IllegalStateException("Illegal opcode: " + code.op(line));
    }
  }
  
  // static only
  private ControlFlowHandler() {
  }
  
}