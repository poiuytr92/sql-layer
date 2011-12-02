/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.expression.std;

import com.akiban.server.expression.Expression;
import com.akiban.server.expression.ExpressionEvaluation;
import com.akiban.server.types.AkType;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.extract.Extractors;
import com.akiban.server.types.extract.LongExtractor;
import com.akiban.server.types.util.AbstractArithValueSource;
import com.akiban.util.ArgumentValidation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public class ArithExpression extends AbstractBinaryExpression
{
    private final ArithOp op;
    protected final AkType topT;

    /**
     * SUPPORTED_TYPES: contains all types that are supported in ArithExpression.
     *
     * Notes about the map's structure:
     * It is important that all date/time types be *put* with an even value key (e.g., 0, 2, 4, ... 2n)
     * and regular numeric types (double, int, etc) odd value key (e.g., 1, 3, 5, ... 2n +1),
     * since these keys are going to be used to determine the return type of the expression
     *
     * INTERVAL is put with '0' because INTERVAL is special; that is, it can "interact" with both date/time types
     * and regular numeric types
     */
    protected static final BidirectionalMap SUPPORTED_TYPES = new BidirectionalMap(9, 0.5f);
    static
    {
        // date/time types : key is even
        SUPPORTED_TYPES.put(AkType.INTERVAL, 0);
        SUPPORTED_TYPES.put(AkType.DATE, 2);
        SUPPORTED_TYPES.put(AkType.TIME, 4);
        SUPPORTED_TYPES.put(AkType.DATETIME, 6);
        SUPPORTED_TYPES.put(AkType.YEAR, 8);

        // regular numeric types: key is odd
        SUPPORTED_TYPES.put(AkType.DECIMAL, 1);
        SUPPORTED_TYPES.put(AkType.DOUBLE, 3);
        SUPPORTED_TYPES.put(AkType.U_BIGINT, 5);
        SUPPORTED_TYPES.put(AkType.LONG, 7);
    }

    public ArithExpression (Expression lhs, ArithOp op, Expression rhs)
    {
        super(getTopType(lhs.valueType(), rhs.valueType(), op),lhs, rhs);
        
        this.op = op; 
        topT = super.valueType();
    }

    @Override
    protected void describe(StringBuilder sb)
    {
        sb.append(op);
    }

    @Override
    public ExpressionEvaluation evaluation()
    {
        return new InnerEvaluation(op, topT, this.childrenEvaluations());
    }

    /**
     * Date/time types:
     *      DATE - DATE, TIME - TIME, YEAR - YEAR , DATETIME - DATETIME, => result is an INTERVAL
     *      DATE + INTERVAL => DATE, TIME + INTERVAL => TIME, ....etc
     *      INTERVAL + DATE => DATE, INTERVAL + TIME => TIME, ....etc
     *      DATE - INTERVAL => DATE, TIME - INTERVAL => TIME, ....etc
     *      INTERVAL + INTERVAL => INTERVAL
     *      INTERVAL - INTERVAL => INTERVAL
     *      INTERVAL * n (of anytypes :double, long ,...etc) => INTERVAL
     *      INTERVAL / n = INTERVAL
     *
     *  Regular types:
     *      Anything [+/*-]  DECIMAL => DECIMAL
     *      Anything (except DECIMAL) [+/*-] DOUBLE = > DOUBLE
     *      Anything (except DECIMAL and DOUBLE) [+/*-] U_BIGINT => U_BIGINT
     *      LONG [+/*-] LONG => LONG
     *
     * Anything else is unsupported
     *
     *
     * @param leftT
     * @param rightT
     * @param op
     * @return topType
     * @author VyNguyen
     *
     * * Why/how this works:
     *      1) find leftT and rightT's key
     *      2) find product of the two keys
     *      3) find sum of the two keys
     *
     *      if sum is neg then both are not supported since .get() only returns -1 if the types aren't in the map
     *      else
     *          if product is zero then at least one of the two is an INTERVAL (key of INTERVAL is zero)
     *              check sum :
     *              if sum is even (0, 2, 4...etc..) then the other operand is either an interval or date/time
     *                  check op : if it's anything other than + or - => throw exception
     *              if sum is odd (1, 3, 5...etc..) then the other operand is a regular numeric type
     *                  check of : if it's anything other than * or / => throw exception
     *          if product is positive, then both are supported types
     *              check product:
     *              if it's odd, then the two operands are both regular numeric (since the product of two numbers can only be odd if both the numbers are odd)
     *              if it's even, at least one of the two is date/time
     *                  the only legal case is when both operands are date, or time, and the operator is minus
     *                  else => throw exception
     *          if product is negative, then one is supported and one is NOT supported
     *              check sum:
     *              if sum is odd => one of the two operand is a date/time and the other is unsupported (since unsupported type get a key of -1, and date/time's key is an even. even -1 = odd)
     *                  in which case, throw an exception
     *              else if sum is even => unsupported and a numeric => return the numeric type
     */
    protected static AkType getTopType (AkType leftT, AkType rightT, ArithOp op)
    {
       if (leftT == AkType.NULL || rightT == AkType.NULL)  return AkType.NULL;
       String msg = leftT.name() + " " + op.opName() + " " + rightT.name();
       int l = SUPPORTED_TYPES.get(leftT), r = SUPPORTED_TYPES.get(rightT);
       int prod = l*r, sum = r + l;

       if (sum <= -1 ) throw new UnsupportedOperationException(msg); // both are NOT supported || interval and a NOT supported
       if (prod == 0) // at least one is interval
       {
           if (sum %2 == 0) // datetime and interval alone
               switch (op.opName())
               {
                  case '-': if (r != 0) throw new UnsupportedOperationException(msg); // fall thru;  check if second operandis NOT interval E.g inteval - date? => nonsense
                  case '+': return SUPPORTED_TYPES.get(sum); // return date/time or interval
                  default: throw new UnsupportedOperationException(msg);
               }
            else // number and interval: an interval can be multiply with || divide by a number
            {
               if (op.opName() == '/' && l == 0 || op.opName() == '*') return AkType.INTERVAL;
               else throw new UnsupportedOperationException(msg);
            }
        }
        else if (prod > 0) // both are supported
        {
            if (prod % 2 == 1) // odd => numeric values only
                return SUPPORTED_TYPES.get(l < r ? l : r);
            else // even => at least one is datetime
            {
                if (l == r && op.opName() == '-') return AkType.INTERVAL;
                else throw new UnsupportedOperationException("");
            }
        }
        else // left || right is not supported
        {
            if( sum %2 == 1) throw new UnsupportedOperationException(msg); // date/times and unsupported
            else return SUPPORTED_TYPES.get(sum+1);
        }   
    }

    @Override
    protected boolean nullIsContaminating()
    {
        return true;
    }
    
  
    private static class InnerEvaluation extends AbstractTwoArgExpressionEvaluation
    {
        @Override
        public ValueSource eval() 
        {  
            valueSource.setOperands(left(), right());
            return valueSource;
        }
        
        private InnerEvaluation (ArithOp op, AkType topT,
                List<? extends ExpressionEvaluation> children)
        {
            super(children);
            valueSource = new InnerValueSource(op, topT);
        }
        
        private final InnerValueSource valueSource;        
    }
    
   private static class InnerValueSource extends AbstractArithValueSource
   {
       private final ArithOp op;
       private ValueSource left;
       private ValueSource right;
       private AkType topT;
       public InnerValueSource (ArithOp op,  AkType topT )
       {
           this.op = op;
           this.topT = topT;
       }
       
       public void setOperands (ValueSource left, ValueSource right)
       {
           ArgumentValidation.notNull("Left", left);
           ArgumentValidation.notNull("Right", right);
           this.left = left;
           this.right = right;
       }
       
       @Override
       public AkType getConversionType () 
       {
          return topT; 
       }

        @Override
        protected long rawLong() 
        {            
            return op.evaluate(Extractors.getLongExtractor(left.getConversionType()).getLong(left),
                    Extractors.getLongExtractor(right.getConversionType()).getLong(right));
        }

        @Override
        protected double rawDouble()
        {
            return op.evaluate(Extractors.getDoubleExtractor().getDouble(left),
                    Extractors.getDoubleExtractor().getDouble(right));
        }  

        @Override
        protected BigInteger rawBigInteger() 
        {                   
           return op.evaluate(Extractors.getUBigIntExtractor().getObject(left),
                   Extractors.getUBigIntExtractor().getObject(right));
        }

        @Override
        protected BigDecimal rawDecimal() 
        {
            return op.evaluate(Extractors.getDecimalExtractor().getObject(left),
                    Extractors.getDecimalExtractor().getObject(right));
        }

        @Override
        protected long rawInterval ()
        {
            /*
             * The rawInterval() is called only if the top's type is INTERVAL, which  only happens when
             *      one of the two operands is an interval and the other is a regular numeric value
             *      or both operands are of the same date/time type, that is, both are date, or both are time, etc
             *
             * DECIMAL's key in SUPPORTED is 1, DOUBLE's key is 3, [...etc...] and INTERVAL'S is 0.
             * So if the difference is -1 or 1 => rawDecimal
             *    if  //               -3 or 3 => rawDouble
             *      [...etc...]
             *    else  (when the difference is either 0 or something different than the above)
                    : must be date/times => rawLong
             *
             */
            int pos = SUPPORTED_TYPES.get(left.getConversionType()) - SUPPORTED_TYPES.get(right.getConversionType());
            switch (pos)
            {
                case -1:
                case 1:     return rawDecimal().longValue();
                case -3:
                case 3:     return (long)rawDouble();
                case -5:
                case 5:     return rawBigInteger().longValue();
                default:    LongExtractor lEx = Extractors.getLongExtractor(left.getConversionType());
                            LongExtractor rEx = Extractors.getLongExtractor(right.getConversionType());
                            long leftUnix = lEx.stdLongToUnix(lEx.getLong(left));
                            long rightUnix = rEx.stdLongToUnix(rEx.getLong(right));               
                            return Extractors.getLongExtractor(SUPPORTED_TYPES.get(pos >= 0 ? pos : -pos)).
                                    unixToStdLong(op.evaluate(leftUnix, rightUnix));
            }
        }

        @Override
        public boolean isNull() 
        {
            return left.isNull() || right.isNull();
        }       
   }
}