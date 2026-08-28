// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements.  See the NOTICE.txt file distributed with this work for
// additional information regarding copyright ownership.  The ASF licenses this
// file to you under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy of
// the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
// License for the specific language governing permissions and limitations under
// the License.

package org.apache.oodt.commons.util;

import org.apache.oodt.commons.io.Base64DecodingInputStream;
import org.apache.oodt.commons.io.Base64EncodingOutputStream;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Base 64 encoding and decoding.
 *
 * This class provides methods for RFC-1521 specified "base 64" encoding and decoding of
 * arbitrary data.  Pass a byte array into the {@link #encode} method and you'll get a
 * byte array result where all of the bytes are printable ASCII values.  Pass that result
 * into {@link #decode} and you'll get your original byte array.
 *
 * <p>Sincere thanks to Tom Daley for providing a sample encoder algorithm and a great
 * explanation of how RFC-1521 is supposed to work.
 *
 * @author Kelly
 */
public class Base64 {
	/** Encode into base 64.
	 *
	 * Encode the given data into RFC-1521 base 64.  Encoding a null array gives a
	 * null result.
	 *
	 * @param data The data to encode.
	 * @return Base-64 encoded <var>data</var>.
	 */
	public static byte[] encode(final byte[] data) {
		return encode(data, 0, data.length);
	}

	/** Encode into base 64.
	 *
	 * Encode the given data into RFC-1521 base 64.  Encoding a null array gives a
	 * null result.  Start encoding at the given offset and go for the given amount of
	 * bytes.
	 *
	 * @param data The data to encode.
	 * @param offset Where to start looking for data to encode.
	 * @param length How much data to encode.
	 * @return Base-64 encoded <var>data</var>
	 */
	public static byte[] encode(final byte[] data, int offset, int length) {
		if (data == null) {
		  return null;
		}
		if (offset < 0 || offset > data.length) {
		  throw new IndexOutOfBoundsException("Can't encode at index " + offset + " which is beyond array bounds 0.."
											  + data.length);
		}
		if (length < 0) {
		  throw new IllegalArgumentException("Can't encode a negative amount of data");
		}
		if (offset + length > data.length) {
		  throw new IndexOutOfBoundsException("Can't encode beyond right edge of array");
		}
		
		int i, j;
		byte dest[] = new byte[((length+2)/3)*4];

		// Convert groups of 3 bytes into 4.
		for (i = offset, j = 0; i < offset + length - 2; i += 3) {
			dest[j++] = (byte) ((data[i] >>> 2) & 077);
			dest[j++] = (byte) ((data[i+1] >>> 4) & 017 | (data[i] << 4) & 077);
			dest[j++] = (byte) ((data[i+2] >>> 6) & 003 | (data[i+1] << 2) & 077);
			dest[j++] = (byte) (data[i+2] & 077);
		}

		// Convert any leftover bytes.
		if (i < offset + length) {
			dest[j++] = (byte) ((data[i] >>> 2) & 077);
			if (i < offset + length - 1) {
				dest[j++] = (byte) ((data[i+1] >>> 4) & 017 | (data[i] << 4) & 077);
				dest[j++] = (byte) ((data[i+1] << 2) & 077);
			} else {
			  dest[j++] = (byte) ((data[i] << 4) & 077);
			}
		}

		// Now, map those onto base 64 printable ASCII.
		for (i = 0; i <j; i++) {
			if      (dest[i] < 26) {
			  dest[i] = (byte) (dest[i] + 'A');
			} else if (dest[i] < 52) {
			  dest[i] = (byte) (dest[i] + 'a' - 26);
			} else if (dest[i] < 62) {
			  dest[i] = (byte) (dest[i] + '0' - 52);
			} else if (dest[i] < 63) {
			  dest[i] = (byte) '+';
			} else {
			  dest[i] = (byte) '/';
			}
		}

		// Pad the result with and we're done.
		for (; i < dest.length; i++) {
		  dest[i] = (byte) '=';
		}
		return dest;
	}

	/** Decode from base 64.
	 *
	 * Decode the given RFC-1521 base 64 encoded bytes into the original data they
	 * represent.  Decoding null data gives a null result.
	 *
	 * @param data Base-64 encoded data to decode.
	 * @return Decoded <var>data</var>.
	 */
	public static byte[] decode(final byte[] data) {
		return decode(data, 0, data.length);
	}

	/** Decode from base 64.
	 *
	 * Decode the given RFC-1521 base 64 encoded bytes into the original data they
	 * represent.  Decoding null data gives a null result.
	 *
	 * @param data Base-64 encoded data to decode.
	 * @param offset Where to start looking for data to decode.
	 * @param length How much data to decode.
	 * @return Decoded <var>data</var>.
	 */
	public static byte[] decode(final byte[] data, int offset, int length) {
		if (data == null) {
		  return null;
		}
		// encode guards with "offset > data.length" and this guarded with
		// ">=", so a zero-length encoding -- which is what encoding an empty
		// payload legitimately produces -- could not be decoded back:
		// decode(encode(new byte[0])) threw IndexOutOfBoundsException. The
		// two agree now.
		if (offset < 0 || offset > data.length) {
		  throw new IndexOutOfBoundsException("Can't decode at index " + offset + " which is beyond array bounds 0.."
											  + data.length);
		}
		if (length < 0) {
		  throw new IllegalArgumentException("Can't decode a negative amount of data");
		}
		if (offset + length > data.length) {
		  throw new IndexOutOfBoundsException("Can't decode beyond right edge of array");
		}

		// Ignore any padding at the end.
		int tail = offset + length - 1;
		while (tail >= offset && data[tail] == '=') {
		  --tail;
		}
		// tail - offset, not tail + offset. tail is an absolute index and
		// offset was being added to it rather than subtracted, so the decoded
		// length came out right only when offset was 0 -- which is what
		// decode(byte[]) passes, and why nobody noticed. At any other offset
		// the result was too long by 2*offset: garbage at best, and with the
		// scratch buffer above, an ArrayIndexOutOfBoundsException.
		//
		// tail - offset + 1 is the encoded length without its padding, and
		// subtracting length/4 turns four 6-bit groups into three bytes.
		byte dest[] = new byte[tail - offset + 1 - length/4];

		// First, convert from base-64 ascii to 6 bit bytes.
		//
		// Into a scratch buffer. This used to write the translation back
		// into the caller's array, so decoding destroyed the encoded buffer
		// it was handed -- and decoding the same buffer twice returned two
		// different answers. Nothing in "public static byte[] decode(byte[])"
		// suggests the argument is consumed, and the method returns a fresh
		// array, which makes it look pure. Any caller keeping the encoded
		// form to log it, to retry a failed send, or simply because it
		// belongs to someone else, had silently lost it.
		byte[] sixBit = new byte[length];
		for (int i = offset; i < offset+length; i++) {
			byte b = data[i];
			if      (b == '=') {
			  b = 0;
			} else if (b == '/') {
			  b = 63;
			} else if (b == '+') {
			  b = 62;
			} else if (b >= '0' && b <= '9') {
			  b = (byte) (b - ('0' - 52));
			} else if (b >= 'a'  &&  b <= 'z') {
			  b = (byte) (b - ('a' - 26));
			} else if (b >= 'A'  &&  b <= 'Z') {
			  b = (byte) (b - 'A');
			}
			sixBit[i - offset] = b;
		}

		// Map those from 4 6-bit byte groups onto 3 8-bit byte groups.
		int i, j;
		for (i = 0, j = 0; j < dest.length - 2; i += 4, j += 3) {
			dest[j]   = (byte) (((sixBit[i] << 2) & 255) | ((sixBit[i+1] >>> 4) & 003));
			dest[j+1] = (byte) (((sixBit[i+1] << 4) & 255) | ((sixBit[i+2] >>> 2) & 017));
			dest[j+2] = (byte) (((sixBit[i+2] << 6) & 255) | (sixBit[i+3] & 077));
		}

		// And get the leftover ...
		if (j < dest.length) {
		  dest[j] = (byte) (((sixBit[i] << 2) & 255) | ((sixBit[i + 1] >>> 4) & 003));
		}
		if (++j < dest.length) {
		  dest[j] = (byte) (((sixBit[i + 1] << 4) & 255) | ((sixBit[i + 2] >>> 2) & 017));
		}

		// That's it.
		return dest;
	}

	/** This class provides namespace for utility methods and shouldn't be instantiated.
	 */
	private Base64() {
		throw new IllegalStateException(getClass().getName() + " should not be instantiated");
	}

	/** Command-line runner that encodes or decodes.
	 *
	 * @param argv Command-line arguments.
	 */
	public static void main(String[] argv) throws IOException {
		if (argv.length < 1 || argv.length > 2) {
			System.err.println("Usage: encode|decode [file]");
			System.exit(1);
		}
		boolean encode = true;
		if ("encode".equals(argv[0])) {
		  encode = true;
		} else if ("decode".equals(argv[0])) {
		  encode = false;
		} else {
			System.err.println("Specify either \"encode\" or \"decode\"");
			System.exit(1);
		}
		InputStream source = argv.length == 2? new BufferedInputStream(new FileInputStream(argv[1])) : System.in;
		InputStream in;
		OutputStream out;
		if (encode) {
			in = source;
			out = new Base64EncodingOutputStream(System.out);
		} else {
			in = new Base64DecodingInputStream(source);
			out = System.out;
		}
		byte[] buf = new byte[512];
		int numRead;
		while ((numRead = in.read(buf)) != -1) {
		  out.write(buf, 0, numRead);
		}
		in.close();
		out.close();
		System.exit(0);
	}
}

			
