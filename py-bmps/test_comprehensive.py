#!/usr/bin/env python3
"""
Comprehensive test script for the simplified API
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
            print(json.dumps(result, indent=2))
            return True
        else:
            print(f"❌ Health check failed: {response.text}")
            return False
    except Exception as e:
        print(f"❌ Health check error: {e}")
        return False

def test_predicted_point_moves():
    """Test the predictedPointMoves endpoint"""
    print("\n=== Testing /predictedPointMoves endpoint ===")
    
    # Sample test data
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
            
            # Check required fields
            required_fields = [
                "predictions_atr", "predictions_points", "atr_value", 
                "time_horizons", "strongest_signal_atr", "strongest_signal_points", 
                "inference_time_ms"
            ]
            
            missing_fields = []
            for field in required_fields:
                if field not in result:
                    missing_fields.append(field)
            
            if missing_fields:
                print(f"❌ Missing required fields: {missing_fields}")
            else:
                print("✅ All required fields present")
            
            print(json.dumps(result, indent=2))
            return len(missing_fields) == 0
            
        else:
            print(f"❌ Point predictions failed: {response.text}")
            return False
            
    except Exception as e:
        print(f"❌ Point predictions error: {e}")
        return False

def test_missing_endpoints():
    """Test endpoints that might be missing"""
    print("\n=== Testing potentially missing endpoints ===")
    
    missing_endpoints = []
    test_data = {
        "features": [0.5, 0.2, 0.1, 0.3, 0.8, 0.6, 0.4, 0.1, 0.2, 0.0, -0.1, 0.3, 0.2, 0.1, 50.0, 0.5, 1.2, 0.1, -0.2, 1.5, -0.8, 0.0]
    }
    
    # Check for endpoints the Scala broker expects
    endpoints_to_check = [
        ("/predict", "POST", test_data),
        ("/predict/batch", "POST", {"features_batch": [test_data["features"]]}),
        ("/predict/signals", "POST", test_data),
        ("/model/info", "GET", None)
    ]
    
    for endpoint, method, data in endpoints_to_check:
        try:
            if method == "GET":
                response = requests.get(f"http://localhost:8001{endpoint}")
            else:
                response = requests.post(f"http://localhost:8001{endpoint}", json=data)
            
            if response.status_code == 404:
                missing_endpoints.append(endpoint)
                print(f"❌ Missing endpoint: {method} {endpoint}")
            elif response.status_code in [200, 422]:  # 422 might be validation error, but endpoint exists
                print(f"✅ Endpoint exists: {method} {endpoint}")
            else:
                print(f"⚠️  Endpoint {method} {endpoint} returned {response.status_code}")
                
        except Exception as e:
            print(f"❌ Error testing {endpoint}: {e}")
            missing_endpoints.append(endpoint)
    
    return missing_endpoints

def test_field_validation():
    """Test field validation on the simplified endpoint"""
    print("\n=== Testing field validation ===")
    
    # Test with wrong number of features
    try:
        wrong_features = {
            "features": [1, 2, 3],  # Only 3 features instead of 22
            "atr_value": 4.25
        }
        response = requests.post("http://localhost:8001/predictedPointMoves", json=wrong_features)
        if response.status_code == 422:
            print("✅ Validation correctly rejects wrong number of features")
        else:
            print(f"❌ Validation failed for wrong features: {response.status_code}")
    except Exception as e:
        print(f"❌ Validation test error: {e}")
    
    # Test with negative ATR
    try:
        negative_atr = {
            "features": [0.5, 0.2, 0.1, 0.3, 0.8, 0.6, 0.4, 0.1, 0.2, 0.0, -0.1, 0.3, 0.2, 0.1, 50.0, 0.5, 1.2, 0.1, -0.2, 1.5, -0.8, 0.0],
            "atr_value": -1.0
        }
        response = requests.post("http://localhost:8001/predictedPointMoves", json=negative_atr)
        if response.status_code == 422:
            print("✅ Validation correctly rejects negative ATR")
        else:
            print(f"❌ Validation failed for negative ATR: {response.status_code}")
    except Exception as e:
        print(f"❌ Validation test error: {e}")

def main():
    """Run all tests"""
    print("🧪 Running comprehensive API tests...\n")
    
    # Wait a moment for server to be ready
    time.sleep(2)
    
    health_ok = test_health_endpoint()
    point_moves_ok = test_predicted_point_moves()
    missing_endpoints = test_missing_endpoints()
    test_field_validation()
    
    print(f"\n{'='*50}")
    print("📊 TEST SUMMARY:")
    print(f"✅ Health endpoint: {'PASS' if health_ok else 'FAIL'}")
    print(f"✅ Point moves endpoint: {'PASS' if point_moves_ok else 'FAIL'}")
    print(f"❌ Missing endpoints: {len(missing_endpoints)} ({', '.join(missing_endpoints) if missing_endpoints else 'None'})")
    
    if missing_endpoints or not health_ok or not point_moves_ok:
        print("\n🚨 Issues found that need to be fixed!")
        return False
    else:
        print("\n🎉 All tests passed!")
        return True

if __name__ == "__main__":
    main()