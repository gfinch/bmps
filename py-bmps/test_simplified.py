#!/usr/bin/env python3
"""
Test script focused only on endpoints the simplified Scala broker uses
"""

import requests
import json
import time

def test_health_endpoint():
    """Test the health endpoint"""
    print("=== Testing /health endpoint ===")
    try:
        response = requests.get("http://localhost:8001/health")
        print(f"Status Code: {response.status_code}")
        if response.status_code == 200:
            result = response.json()
            print("✅ Health check passed")
            
            # Check fields the Scala broker expects
            expected_fields = ["status", "model_loaded", "inference_time_ms"]
            missing_fields = [f for f in expected_fields if f not in result]
            
            if missing_fields:
                print(f"❌ Missing expected fields: {missing_fields}")
                return False
            else:
                print("✅ All expected fields present")
                
            print(json.dumps(result, indent=2))
            return True
        else:
            print(f"❌ Health check failed: {response.text}")
            return False
    except Exception as e:
        print(f"❌ Health check error: {e}")
        return False

def test_predicted_point_moves():
    """Test the predictedPointMoves endpoint that matches Scala expectations"""
    print("\n=== Testing /predictedPointMoves endpoint ===")
    
    # Sample test data matching Scala broker request
    data = {
        "features": [0.5, 0.2, 0.1, 0.3, 0.8, 0.6, 0.4, 0.1, 0.2, 0.0, -0.1, 0.3, 0.2, 0.1, 50.0, 0.5, 1.2, 0.1, -0.2, 1.5, -0.8, 0.0],
        "atr_value": 4.25
    }
    
    try:
        response = requests.post("http://localhost:8001/predictedPointMoves", json=data)
        print(f"Status Code: {response.status_code}")
        
        if response.status_code == 200:
            result = response.json()
            print("✅ Point predictions succeeded")
            
            # Check fields the Scala broker expects
            expected_fields = ["predictions_atr", "predictions_points", "time_horizons", "inference_time_ms"]
            missing_fields = [f for f in expected_fields if f not in result]
            
            if missing_fields:
                print(f"❌ Missing expected fields: {missing_fields}")
                return False
            else:
                print("✅ All expected fields present")
            
            # Verify data types and structure
            if not isinstance(result["predictions_atr"], list) or len(result["predictions_atr"]) != 5:
                print(f"❌ predictions_atr should be list of 5 floats, got: {result['predictions_atr']}")
                return False
                
            if not isinstance(result["predictions_points"], list) or len(result["predictions_points"]) != 5:
                print(f"❌ predictions_points should be list of 5 floats, got: {result['predictions_points']}")
                return False
                
            if not isinstance(result["time_horizons"], list) or len(result["time_horizons"]) != 5:
                print(f"❌ time_horizons should be list of 5 strings, got: {result['time_horizons']}")
                return False
            
            # Verify conversion math
            print("\n🔍 Verifying ATR to points conversion:")
            atr_values = result["predictions_atr"]
            point_values = result["predictions_points"]
            atr_multiplier = data["atr_value"]
            
            conversion_ok = True
            for i, (atr_val, point_val) in enumerate(zip(atr_values, point_values)):
                expected_point = atr_val * atr_multiplier
                if abs(point_val - expected_point) > 0.01:  # Allow small rounding differences
                    print(f"❌ Conversion error at index {i}: {atr_val} * {atr_multiplier} = {expected_point}, got {point_val}")
                    conversion_ok = False
                else:
                    print(f"✅ Index {i}: {atr_val} * {atr_multiplier} = {point_val}")
            
            print(f"\n📋 Response structure:")
            print(json.dumps(result, indent=2))
            
            return conversion_ok
            
        else:
            print(f"❌ Point predictions failed: {response.text}")
            return False
            
    except Exception as e:
        print(f"❌ Point predictions error: {e}")
        return False

def main():
    """Run tests for the simplified API"""
    print("🧪 Testing simplified API against Scala broker expectations...\n")
    
    # Wait for server
    time.sleep(1)
    
    health_ok = test_health_endpoint()
    point_moves_ok = test_predicted_point_moves()
    
    print(f"\n{'='*50}")
    print("📊 SIMPLIFIED API TEST SUMMARY:")
    print(f"✅ Health endpoint: {'PASS' if health_ok else 'FAIL'}")
    print(f"✅ Point moves endpoint: {'PASS' if point_moves_ok else 'FAIL'}")
    
    if health_ok and point_moves_ok:
        print("\n🎉 All tests passed! API matches Scala broker expectations.")
        return True
    else:
        print("\n🚨 Some tests failed!")
        return False

if __name__ == "__main__":
    main()