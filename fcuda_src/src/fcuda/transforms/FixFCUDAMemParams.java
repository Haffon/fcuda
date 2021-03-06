//============================================================================//
//    FCUDA
//    Copyright (c) <2016> 
//    <University of Illinois at Urbana-Champaign>
//    <University of California at Los Angeles> 
//    All rights reserved.
// 
//    Developed by:
// 
//        <ES CAD Group & IMPACT Research Group>
//            <University of Illinois at Urbana-Champaign>
//            <http://dchen.ece.illinois.edu/>
//            <http://impact.crhc.illinois.edu/>
// 
//        <VAST Laboratory>
//            <University of California at Los Angeles>
//            <http://vast.cs.ucla.edu/>
// 
//        <Hardware Research Group>
//            <Advanced Digital Sciences Center>
//            <http://adsc.illinois.edu/>
//============================================================================//

package fcuda.transforms;

import java.util.*;

import fcuda.common.*;

import cetus.hir.*;

public class FixFCUDAMemParams extends KernelTransformPass
{
  // return pass name
  public String getPassName() {
    return new String("[FixFCUDAMemParams-FCUDA]");
  }

  // constructor
  public FixFCUDAMemParams(Program program)
  {
    super(program);
  }

  private static HashMap<String, VariableDeclarator> mPtr2Offset = new HashMap<String, VariableDeclarator>();

  // given an identifier, check to see if it is in a list of IDs to remove
  private boolean child_matches(List<String> removals, String id) {
    int i;
    for (i = 0; i < removals.size();i++) {
      if (removals.get(i).equals(id)) {
        System.out.println("id: " + removals.get(i));
        return true;
      }
    }
    return false;
  }

  // given a list of ports to remove, get the declarations from the 
  // declarator matching the IDs given in that list.
  private List<Declaration> getMatchingDecls(Declarator d, 
      List<String> removals) {
    System.out.println("in getMatchingDecl");
    List<Declaration> children =  d.getParameters();
    List<Declaration> valid_children = new ArrayList<Declaration>();
    System.out.println(children);
    System.out.println(valid_children);
    int i;
    System.out.println("Removals: " + removals);
    for  (i = 0; i < children.size(); i = i + 1) {
      System.out.println("child: "  + children.get(i));
      List<IDExpression> ids = children.get(i).getDeclaredIDs();
      System.out.println(ids);
      if (ids.size() != 1) {
        System.out.println("error: size is not 1");
        // exit
        return null; 
      } else {
        String name = ids.get(0).getName();
        System.out.println(name);
        System.out.println(removals);
        if (child_matches(removals,name)) {
          valid_children.add(children.get(i));
        }

      }
    }
    System.out.println("before exit");
    System.out.println(valid_children);
    return valid_children;
  }

  // given two lists of specifiers, return true if they are identical or 
  // false if they are not.  We do this because autoESL can only handle 
  // casting if the types match, otherwise it throws an error
  private boolean specifiersMatch(List<Specifier> a, List<Specifier> b) {
    if (a.size() != b.size()) {
      return false;
    } else {
      for (int i = 0; i < a.size(); i = i + 1) {
        if (!a.get(i).toString().equals(b.get(i).toString())) {
          return false;
        }
      }
      return true;
    }
  }

  // given a list of declarations to remove, verify first that the types of 
  // each match E.g. given: 
  //   float* a, float* b, float* c 
  //     => return list of // specs; 
  //   int*   a, float* b, float* c 
  //     => return NULL
  private List<Specifier> validateTypes(List<Declaration> params) {
    VariableDeclaration v, v2;
    System.out.println(params);
    if (params.size() == 0) {
      return null; 
    } else if (params.size() == 1) {
      v = (VariableDeclaration) params.get(0);
      return v.getSpecifiers();
    } else {
      v = (VariableDeclaration) params.get(0);
      List<Specifier> fs = v.getSpecifiers();
      System.out.println("VariableDeclaration: " + v.getDeclarator(0).getSpecifiers());
      if (v.getSpecifiers().get(0) instanceof PointerSpecifier) {
        System.out.println("We got a ptr specifier");
      }
      for (int i = 1; i < params.size(); i++) {
        v2 = (VariableDeclaration) params.get(i);
        System.out.println(v2);
        System.out.println(fs);
        if ((!specifiersMatch(fs, v2.getSpecifiers())) || 
            (!specifiersMatch(v.getDeclarator(0).getSpecifiers(),v2.getDeclarator(0).getSpecifiers()))) {
          return null;
        }
      }
      return fs;
    }
  }

  // given a name, look up the offset
   String getOffsetFromName(String name, List<Annotation> an) {
    int i;
    for (i = 0; i < an.size(); i = i + 1) {
      if (an.get(i).get("remove_port_name").equals(name)) {
        return an.get(i).get("offset");
      }
    }
    return null;
  }

  // get first statement in a compoundStatement that is not a variable 
  // declaration
   private Statement getFirstNonDeclarationStatement(CompoundStatement cs) {
    Statement s = null;  
    List<Traversable> ch = cs.getChildren();
    for (int j = 0; j < ch.size(); j++) {
      if (ch.get(j) instanceof Statement && !(ch.get(j) instanceof DeclarationStatement)) {
        s = (Statement)ch.get(j);
        break;
      }
    }
    return s;
  }

  private void fixMemoryPorts(Procedure proc) {
    // get annotations => this gives us which ports to rename
    List<Annotation> remAnnotations  = new java.util.ArrayList<Annotation>();
    HashMap<String, String> mergePorts = new HashMap<String, String>();
    HashMap<String, ArrayList<String>> merged2OrigPorts = new HashMap<String, ArrayList<String>>();
    int a; 
    System.out.println("Proc: " + proc);
    boolean containsNOCpragma = false;
    for (Annotation annot : proc.getAnnotations()) {
      if (annot.get("fcuda") == "portmerge") {
        containsNOCpragma = true;
        break;
      }
    }
    if (!containsNOCpragma) {
      System.out.println("This proc does not contain remove ports pragmas. Skip.");
      return;
    }

    for (a = 0; a < proc.getAnnotations().size();a++) {
      Annotation an = proc.getAnnotations().get(a);
      if (an.get("remove_port_name") != null) {
        remAnnotations.add(an);
        String portName = an.get("remove_port_name");
        String portID = an.get("port_id");
        String portCoreID = an.get("port_core");
        String newPortName;
        if (portCoreID != null)
          newPortName = "memport_core" + portCoreID + "_p" + portID;
        else
          newPortName = "memport" + "_p" + portID;
        if (!merged2OrigPorts.containsKey(newPortName)) {
          ArrayList<String> listPorts = new ArrayList<String>();
          merged2OrigPorts.put(newPortName, listPorts);
        }
        merged2OrigPorts.get(newPortName).add(portName);

        if (an.get("data_pack") != null && 
            an.get("data_pack").toString().equals("yes")) {
          String hlsDataPackPragma = new String("HLS DATA_PACK variable=" + newPortName);
          FCUDAGlobalData.addHLSPragma(proc, new PragmaAnnotation(hlsDataPackPragma));
        } 
      }
    }
    for (String newPortStr : merged2OrigPorts.keySet()) {
      ArrayList<String> toRemove =  merged2OrigPorts.get(newPortStr);
      Declarator d = proc.getDeclarator();
      System.out.println(d);
      List<Declaration> params = getMatchingDecls(d,toRemove);
      // Check for matching types
      List<Specifier> specs = validateTypes(params);
      if (specs == null)  {
        throw new RuntimeException("The types are not valid!!! All types of memory ports must match.");
      }

      NameID memName = new NameID(newPortStr);
      VariableDeclarator v = new VariableDeclarator( ((VariableDeclaration)params.get(0)).getDeclarator(0).getSpecifiers(), memName);
      VariableDeclaration vd  = new VariableDeclaration(specs,v);

      System.out.println(specs);
      System.out.println(vd);

      int i;
      for (i = 0; i < params.size();i = i + 1) {
        proc.removeDeclaration(params.get(i));
      }

      proc.addDeclaration(vd);

      for (i = 0 ; i < params.size(); i = i + 1) {
        Declaration cp = params.get(i);
        String name = cp.getDeclaredIDs().get(0).getName();

        NameID newVar = new NameID(name);
        VariableDeclarator nv = new VariableDeclarator( ((VariableDeclaration)params.get(0)).getDeclarator(0).getSpecifiers(), newVar);
        VariableDeclaration nvd  = new VariableDeclaration(specs,nv);
        // Now, put this into the body
        if (!proc.getBody().getDeclarations().isEmpty())
          proc.getBody().addDeclarationBefore(
              (Declaration)proc.getBody().getDeclarations().toArray()[0],nvd);
        else
          proc.getBody().addDeclaration(nvd);
        // Now add an assignment statement...
        // a = &memport[0]
        //

        Statement s = getFirstNonDeclarationStatement(proc.getBody());

        if (s == null) {
          throw new RuntimeException("Could not rename memory ports: could not find initial statement");
        }
        System.out.println(name);
        System.out.println(name + " " + remAnnotations);
        String offsetName = getOffsetFromName(name, remAnnotations);
        if (offsetName == null) {
          offsetName = name + "_addr";
          VariableDeclarator offsetV = new VariableDeclarator(new NameID(offsetName));
          mPtr2Offset.put(name, offsetV);
          VariableDeclaration offsetVd  = new VariableDeclaration(Specifier.INT, offsetV);
          proc.addDeclaration(offsetVd);
        }

        Expression offset = new NameID(offsetName);
        proc.getBody().addStatementBefore(s, new ExpressionStatement(
              new AssignmentExpression(new Identifier(nv),
                AssignmentOperator.NORMAL, new UnaryExpression(
                  UnaryOperator.ADDRESS_OF, new ArrayAccess( new Identifier(v), 
                    offset
                    )))));
      }

      String the_name = memName.getName();

      String pragma_annot = new String("HLS interface ap_bus port=" + the_name);

      System.out.println("Annot is: " + (new PragmaAnnotation(pragma_annot)));
      // Add pragma to list
      FCUDAGlobalData.addHLSPragma(proc, new PragmaAnnotation(pragma_annot));

      pragma_annot = new String("HLS RESOURCE variable=" + the_name + " core=AXI4M");
      FCUDAGlobalData.addHLSPragma(proc, new PragmaAnnotation(pragma_annot));
    }
  }

  private void fixBramPorts(Procedure proc) {

    // find the matching annotations and statements, this tells us which
    // BRAMs to turn into ports
    List<Annotation> remAnnotations  = new java.util.ArrayList<Annotation>();
    List<String> toRemove =  new ArrayList<String>();

    CompoundStatement body = proc.getBody();

    // There should really an iterator option to iterate over only a 
    // given class. E.g. iterate over all statements, annotatables, etc
    DepthFirstIterator<Traversable> dfi = new DepthFirstIterator<Traversable>(body);
    while (dfi.hasNext()) {
      Traversable t = dfi.next();
      if (t instanceof Annotatable) {
        Annotatable s = (Annotatable)t;
        if (s.getAnnotations() != null) {
          for (Annotation an : s.getAnnotations()) {
            if (an.get("remove_port_name") != null) {
              toRemove.add((String)an.get("remove_port_name"));
              remAnnotations.add(an);
            }
          }
        }
      }
    }

    // We now have the matching annotations
    // We need to create new variable declarations for each
    // and add this declaration to the process

    for (Annotation an : remAnnotations) {
      Annotatable a = an.getAnnotatable();
      if (a instanceof DeclarationStatement){
        Declaration d = ((DeclarationStatement)a).getDeclaration();
        if (d instanceof VariableDeclaration) {
          // This should remove the original from the body
          VariableDeclaration orig = (VariableDeclaration)d;
          VariableDeclarator orig_dl = (VariableDeclarator)orig.getDeclarator(0);
          System.out.println("orig: " + orig);
          System.out.println("orig_dl: " + orig_dl);

          a.getParent().removeChild(a);

          // Now: we need to copy the specs, name of original...
          // FIXME: This assumes just one declared ID
          String name = d.getDeclaredIDs().get(0).getName();

          // set up new variable for parameter
          NameID newName = new NameID(name); 
          VariableDeclarator nv = new VariableDeclarator(orig_dl.getSpecifiers(), newName, orig_dl.getArraySpecifiers());
          VariableDeclaration vd = new VariableDeclaration(orig.getSpecifiers(),nv); 
          proc.addDeclaration(vd);

          FCUDAGlobalData.addOutsideBRAM(proc, name);

          //Tag BRAM
          NameID newNameTagBRAM = new NameID(name + "_tag"); 
          VariableDeclarator nvTagBRAM = new VariableDeclarator(newNameTagBRAM, orig_dl.getArraySpecifiers());
          VariableDeclaration vdTagBRAM  = new VariableDeclaration(Specifier.INT, nvTagBRAM);
          proc.addDeclaration(vdTagBRAM);

          //Index pointer
          //NameID newNameIndexPtr = new NameID(name + "_index"); 
          //VariableDeclarator nvIndexPtr = new VariableDeclarator(PointerSpecifier.UNQUALIFIED, newNameIndexPtr);
          //VariableDeclaration vdIndexPtr  = new VariableDeclaration(Specifier.INT, nvIndexPtr);
          //proc.addDeclaration(vdIndexPtr);

          String bram_name = name;
          String interface_pragma_annot = new String("HLS interface ap_memory port=" + bram_name);
          FCUDAGlobalData.addHLSPragma(proc, new PragmaAnnotation(interface_pragma_annot));

          //Support 1-port BRAM for now
          String resource_pragma_annot = new String("HLS resource variable=" + bram_name + " core=RAM_1P_BRAM");
          FCUDAGlobalData.addHLSPragma(proc, new PragmaAnnotation(resource_pragma_annot));

        }
      }
    }

    //Find an assignment statement like: on-chip = off-chip (READ)
    //then add tag BRAM statement
    //Note: now only check whether both LHS and RHS are array access
    //Probably need to extend to broader cases like pointer access ...
    //It is only used for NoC for now
    DepthFirstIterator iter = new DepthFirstIterator(proc);
    iter.pruneOn(Expression.class);

    while(iter.hasNext()) {
      Statement stmt = null;

      try {
        stmt = (Statement)iter.next(Statement.class);
      }
      catch (NoSuchElementException e) {
        break;
      }
      // Looking only for assignments
      if (!(stmt instanceof ExpressionStatement))
        continue;
      Expression expr = ((ExpressionStatement)stmt).getExpression();
      if (!(expr instanceof AssignmentExpression))
        continue;

      Expression LHSExpr = ((AssignmentExpression)expr).getLHS();
      Expression RHSExpr = ((AssignmentExpression)expr).getRHS();

      if (!((LHSExpr instanceof ArrayAccess) && (RHSExpr instanceof ArrayAccess)))
        continue;
      if (!(mPtr2Offset.containsKey(((ArrayAccess)RHSExpr).getArrayName().toString())))
        continue;

      VariableDeclarator offset = mPtr2Offset.get(((ArrayAccess)RHSExpr).getArrayName().toString());

      if (FCUDAGlobalData.isOutsideBRAM(proc, ((ArrayAccess)LHSExpr).getArrayName().toString())) {
        String tagBRAMName = ((ArrayAccess)LHSExpr).getArrayName().clone().toString() + "_tag";
        IDExpression tagBRAMExpr = (IDExpression)new NameID(tagBRAMName);

        List<Expression> bramIndices = ((ArrayAccess)LHSExpr).getIndices();
        List<Expression> bramIndices_clone = new LinkedList<Expression>();
        for (Expression e : bramIndices)
          bramIndices_clone.add(e.clone());
        ArrayAccess tagbramArg = new ArrayAccess(tagBRAMExpr.clone(),
                          bramIndices_clone);
        Expression tagAddress = new BinaryExpression(
            (IDExpression) new Identifier(offset.clone()),
            BinaryOperator.ADD,
            ((ArrayAccess)RHSExpr).getIndex(0).clone());
        ExpressionStatement tagStmt = new ExpressionStatement(
            new AssignmentExpression(
              tagbramArg,
              AssignmentOperator.NORMAL,
              tagAddress));

        //Add dummy If condition to make sure tag BRAM is accessed
        //before memory read (and data BRAM). Unfortunately, it will
        //certainly degrade the performance.
        IfStatement dummyCheck = new IfStatement(
          new BinaryExpression(
            tagbramArg.clone(), 
            BinaryOperator.COMPARE_GE, 
            new IntegerLiteral(0)),
          stmt.clone());

        FCUDAGlobalData.addTagBRAMStmt(proc, tagStmt);

        CompoundStatement cstmt = (CompoundStatement)stmt.getParent();
        cstmt.addStatementBefore(stmt, dummyCheck);
        cstmt.addStatementBefore(dummyCheck, tagStmt);
        stmt.detach();
      }
    }

  }



  public void transformProcedure(Procedure proc) {
    // combine memory ports into a single in args, move the existing params
    // to function body and assign them
    // Must be specified by a pragma as of now
    fixMemoryPorts(proc);

    // move BRAM ports to function params... 
    // to function body and assign them
    // Must be specified by a pragma as of now
    fixBramPorts(proc);
  }
} 
